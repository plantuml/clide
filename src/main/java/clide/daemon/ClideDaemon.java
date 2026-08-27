package clide.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import clide.PrintMode;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandDispatcher;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.core.TransactionStack;
import clide.jdtls.EclipseProjectFiles;
import clide.jdtls.JdtlsHome;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient.TimeoutException;
import clide.lua.LuaBridge;

/**
 * The long-lived side of clide: owns the single JdtlsSession for a project and
 * a local TCP ServerSocket, and keeps both alive across many separate client
 * connections instead of paying jdtls' handshake and full workspace build again
 * every time - see CLAUDE.md. Main.main() is this class's only caller now:
 * "java -jar clide.jar [--human] &lt;project&gt;" constructs and runs a
 * ClideDaemon directly, in the foreground - see Main's class doc for what that
 * changed from the previous, Java-client architecture (ClideClient no longer
 * exists; the client is clide.py).
 *
 * printMode is fixed for this daemon's entire lifetime - decided once by
 * whoever started it (see Main), never renegotiated by a connection. It is
 * handed to the ClideContext this daemon serves every connection through (see
 * run()), which is also where a command reads it back (ClideContext.getPrintMode()).
 *
 * Client connections are served one at a time (see connectionLock below):
 * jdtls itself only ever handles one request at a time anyway, and clide is a
 * single-user tool, so added concurrency in command *execution* would buy
 * nothing. A client
 * disconnecting (EOF on its socket, the normal end of a clide.py run) only
 * ends that connection - the daemon keeps running for the next one.
 * "exit"/"quit" (see DisconnectCommand) additionally stop the jdtls session
 * itself but still leave the daemon up - CommandDispatcher restarts it lazily
 * the next time a command actually needs it. Only "terminate" (see
 * TerminateCommand) shuts the whole daemon down.
 *
 * A connection announcing "--lua" is served differently: it carries one Lua
 * script rather than a stream of commands - see runScript() and ConnectionMode.
 *
 * "Served one at a time" used to mean one thread, full stop: run()'s own loop
 * called accept() and then did the serving itself, so a second client trying
 * to connect while the first was still being served simply had no thread free
 * to answer it - its connection sat there, accepted at the TCP level (the
 * ServerSocket's own backlog) but never read from, indistinguishable from a
 * dead daemon until whatever it was waiting on (a rebuild can legitimately
 * take minutes - see JdtlsSession.build()) finally finished or timed out. It
 * is still one client served at a time - jdtls still only ever handles one
 * request at a time - but now via connectionLock (a plain mutual-exclusion
 * lock) rather than via there being only one thread able to serve anyone at
 * all: acceptClient() hands every connection its own thread, and
 * serveOrRejectIfBusy() waits a bounded amount of time for its turn
 * (awaitConnectionSlot(), see connectionLock's own doc) before giving up and
 * telling a second connection BUSY (see rejectBusy()) - bounded, so this is
 * still nothing like the old indistinguishable-from-dead hang, just no longer
 * an instant refusal for the ordinary case of a command that finishes well
 * inside that bound. As a side effect of every connection running on its own
 * thread, a bug that throws all the way out of one connection's command now
 * only takes that one connection down, not the whole process - see
 * serveOrRejectIfBusy()'s own doc.
 */
public final class ClideDaemon {

	private final Path projectRoot;
	private final PrintMode printMode;
	private final Collection<Command> commands;

	/**
	 * Held for as long as one connection is being served - see this class' own
	 * doc for why this exists at all. Never lock() - a connection has to be
	 * able to give up rather than wait forever, since jdtls only ever handles
	 * one request at a time and a rebuild can legitimately take minutes (see
	 * this class' own doc) - but not the bare no-argument tryLock() either any
	 * more: awaitConnectionSlot() waits up to BUSY_WAIT_SECONDS via repeated
	 * short-timeout tryLock(long, TimeUnit) calls instead of refusing on the
	 * first miss, so a second connection that arrives while an ordinary
	 * command is still running gets served automatically once that command
	 * finishes, rather than being told BUSY for something that was already
	 * about to clear up. Only a connection still waiting once the whole budget
	 * is spent - or one that arrives while jdtls is genuinely stuck well past
	 * what any of this daemon's own commands should take - ever reaches
	 * rejectBusy(). The short per-attempt timeout (see
	 * BUSY_WAIT_POLL_MILLIS), not one long blocking wait, is what lets a
	 * waiting connection also notice a "terminate" requested in the meantime
	 * (see awaitConnectionSlot()) instead of sitting past it.
	 */
	private final ReentrantLock connectionLock = new ReentrantLock();

	/**
	 * How long serveOrRejectIfBusy() waits for connectionLock to free up
	 * before giving up and rejecting a connection BUSY - see
	 * awaitConnectionSlot(). Comfortably above every ordinary command's own
	 * cost (a full rebuild is ~9-14s on a PlantUML-sized project, see
	 * JDTLS.md/LUA.md; rejectBusy()'s own message still separately warns a
	 * rebuild "can take up to 5 minutes" for the rarer, larger case that even
	 * this budget will not cover), so the common case - a second connection
	 * arriving while the first is mid-command, not mid-crisis - is served
	 * automatically instead of making its caller retry by hand.
	 */
	private static final long BUSY_WAIT_SECONDS = 60;

	/**
	 * The per-attempt timeout awaitConnectionSlot() waits on tryLock(long,
	 * TimeUnit) for, repeated up to BUSY_WAIT_SECONDS total - short enough
	 * that a "terminate" requested by whoever currently holds connectionLock
	 * is noticed within about a second of connectionLock actually freeing up,
	 * rather than this thread sleeping past it for however much of
	 * BUSY_WAIT_SECONDS was left. Mirrors the same tradeoff
	 * serverSocket.setSoTimeout(1000) already makes in run()'s own accept
	 * loop, for the same reason - see run()'s own doc.
	 */
	private static final long BUSY_WAIT_POLL_MILLIS = 1000;

	/**
	 * What the connection currently holding connectionLock is doing, for
	 * rejectBusy() to report to everyone else - written under the lock (see
	 * runSession()), read without it by threads that only want to build a BUSY
	 * message. A torn or stale read only makes that message's elapsed time
	 * slightly off, never anything worse, so no further synchronization is worth
	 * it. Null whenever the lock's holder is not actually running a command
	 * right now (e.g. sitting at a --human prompt between commands, or reading a
	 * MULTI_LINE parameter off a slow client) - rejectBusy() still says BUSY
	 * then, just without a command name to name.
	 */
	private volatile String currentCommand;
	private volatile long currentCommandStartedAtNanos;

	/**
	 * Set once in run(), as soon as the session exists - null only for the
	 * brief window before that. Read (without connectionLock: see
	 * currentCommand's own doc for why that is fine) by rejectBusy() to say
	 * *why* the daemon is busy when the reason is jdtls still indexing in the
	 * background rather than a second genuine client - see
	 * JdtlsSession.isIndexingComplete()/lastStatus().
	 */
	private volatile JdtlsSession session;

	public ClideDaemon(final Path projectRoot, final PrintMode printMode, final Collection<Command> commands) {
		this.projectRoot = projectRoot;
		this.printMode = printMode;
		this.commands = commands;
	}

	public void run() throws IOException, InterruptedException, TimeoutException {
		System.out.println("*** clide daemon starting for " + projectRoot + " (mode: "
				+ (printMode == PrintMode.HUMAN ? "--human" : "--ia") + ")");

		System.out.print("(1/4) Checking for a leftover transaction state ...");
		TransactionStack.refuseIfDirty(projectRoot);
		EclipseProjectFiles.refuseIfDirty(projectRoot);
		System.out.println(" [OK]");

		final boolean eclipseFilesWereMissing = hasEclipseFiles() == false;

		System.out.print("(2/4) Initializing IDE ...");
		final Path jdtlsHome = JdtlsHome.resolve();
		final JdtlsLauncher launcher = new JdtlsLauncher(jdtlsHome, projectRoot);
		final Md5Repository md5Repository = new Md5Repository(projectRoot);
		final FilesRepository filesRepository = new FilesRepository(projectRoot, md5Repository);
		final JdtlsSession session = new JdtlsSession(launcher, filesRepository);
		this.session = session;
		// Says where jdtls lives, because nothing else does and the answer is not
		// guessable: it is a shared per-user cache directory named after the
		// archive's fingerprint, not anything under this project - see JdtlsHome.
		System.out.println(" [OK] (jdtls: " + jdtlsHome + ")");

		System.out.print("(3/4) Starting session ...");
		// start()+build() together in one try/finally, but the finally now only ever
		// fires restoreEclipseFiles() on the FAILURE path: a daemon that fails here
		// never reaches shutdown() (see below), so without this fallback a project's
		// own .project/.classpath would be left stranded in .clide/tmp/ forever -
		// refuseIfDirty() exists to catch exactly that on the next start, but there
		// is no reason to force the user through that recovery when this method can
		// just as well leave things clean itself.
		//
		// On success, restoreEclipseFiles() is deliberately NOT called here any more
		// - only from shutdown(), below. See EclipseProjectFiles' class doc for why
		// restoring right after the initial build turned out not to be safe after
		// all.
		boolean startedAndBuilt = false;
		try {
			session.start();
			System.out.println(" [OK]");

			System.out.print("(4/4) Building project ...");
			session.build();
			startedAndBuilt = true;
		} finally {
			if (startedAndBuilt == false)
				session.restoreEclipseFiles();
		}

		if (eclipseFilesWereMissing)
			System.out.println(" [OK] (imported via a temporary .project/.classpath from src/**/java and .clide/*.jar, "
					+ "kept in place for as long as this daemon runs - none existed before)");
		else
			System.out.println(" [OK] (imported via a temporary .project/.classpath, kept in place for as long as "
					+ "this daemon runs - the project's own restored on shutdown, see .clide/tmp/ for what was "
					+ "actually used)");

		final ClideContext context = new ClideContext(filesRepository, session, commands);
		// Fixed for the daemon's whole lifetime - see this class's own doc and
		// Main. Set once here, never touched again: unlike maxResults, printMode is
		// deliberately left out of resetPerConnectionSettings().
		context.setPrintMode(printMode);

		final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		// Otherwise this thread would sit inside accept() with nothing to wake it -
		// harmless before this change (there was nothing else for it to do), but now
		// it is also this loop's only chance to notice isShutdownRequested() after a
		// "terminate" that some OTHER connection's thread set (see
		// serveOrRejectIfBusy()): with each connection on its own thread, this loop
		// no longer returns from serving one client in between to check.
		serverSocket.setSoTimeout(1000);
		DaemonLock.write(projectRoot, serverSocket.getLocalPort());
		Runtime.getRuntime()
				.addShutdownHook(new Thread(() -> shutdown(session, serverSocket), "clide-daemon-shutdown"));
		System.out.println("Daemon ready on port " + serverSocket.getLocalPort());

		while (context.isShutdownRequested() == false)
			acceptClient(serverSocket, context);

		shutdown(session, serverSocket);
	}

	/**
	 * Accepts the next connection and immediately hands it to its own thread,
	 * returning at once to accept whatever comes after it - see this class' own
	 * doc for why a second connection is now welcomed at the socket level even
	 * while the first is still being served: only one of them will ever actually
	 * run a command (see connectionLock), and the other waits its turn, bounded,
	 * rather than hanging silently the way it used to (see
	 * serveOrRejectIfBusy(), awaitConnectionSlot(), rejectBusy()).
	 *
	 * setDaemon(true): belt and suspenders alongside awaitConnectionSlot()'s own
	 * shutdown check, not a substitute for it - a thread parked in this daemon's
	 * bounded wait already notices "terminate" on its own within about a second
	 * (see BUSY_WAIT_POLL_MILLIS) and exits on its own well before this would
	 * ever matter. It exists for whatever awaitConnectionSlot() does not
	 * anticipate: main() never calls System.exit() (see Main.main()), so the
	 * JVM only exits on its own once every non-daemon thread has finished, and
	 * before this class waited at all, a "clide-client" thread's own lifetime
	 * was always short enough that this was never worth thinking about. A
	 * thread that can now legitimately sit for up to BUSY_WAIT_SECONDS is
	 * worth not being able to outlive "terminate" by mistake.
	 */
	private void acceptClient(final ServerSocket serverSocket, final ClideContext context) throws IOException {
		final Socket client;
		try {
			client = serverSocket.accept();
		} catch (final SocketTimeoutException e) {
			return; // nobody connected in the last second - just gives run() a chance to notice shutdown
		}

		final Thread clientThread = new Thread(() -> serveOrRejectIfBusy(client, context), "clide-client");
		clientThread.setDaemon(true);
		clientThread.start();
	}

	/**
	 * awaitConnectionSlot()'s two outcomes: this thread is now the one and only
	 * connection being served, exactly as when a single thread served every
	 * connection in turn (see this class' own doc) - or connectionLock never
	 * freed up within BUSY_WAIT_SECONDS (or "terminate" was requested while
	 * this thread was still waiting for it), and the client is told BUSY and
	 * closed (see rejectBusy()) rather than left to hang on a read that may
	 * never come back.
	 *
	 * Everything that reads or writes ClideContext - resetPerConnectionSettings()
	 * included - happens only after this thread actually holds connectionLock,
	 * so at most one thread ever touches context at a time, the same invariant
	 * the old single-thread design got for free.
	 *
	 * The RuntimeException catch is new, and worth calling out: before this
	 * change, the daemon had exactly one thread for its entire life, so an
	 * escaping RuntimeException here (a bug CommandDispatcher did not expect)
	 * unwound all the way out of run() and killed the whole process - see
	 * ResultEnvelope.unexpectedPayload()'s own doc, written for exactly that
	 * failure mode, and Main.main(), which catches nothing either. Every
	 * connection now runs on its own thread, so the same bug today only ends
	 * that one connection; printStackTrace() rather than swallowing it, so
	 * whoever is running this daemon in the foreground still sees it happened.
	 */
	private void serveOrRejectIfBusy(final Socket client, final ClideContext context) {
		if (awaitConnectionSlot(context) == false) {
			rejectBusy(client);
			return;
		}

		try {
			// fresh connection: an earlier exit/quit must not leak into this one, and
			// neither must a max_results somebody else set - see ClideContext.
			context.resetPerConnectionSettings();
			try (Socket c = client;
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
					PrintStream out = new PrintStream(c.getOutputStream(), true, StandardCharsets.UTF_8)) {
				runSession(reader, out, context);
			} catch (final IOException e) {
				// this client's connection broke - the daemon stays up for the next one
			} catch (final RuntimeException e) {
				e.printStackTrace();
			}
		} finally {
			currentCommand = null;
			connectionLock.unlock();
		}
	}

	/**
	 * Waits up to BUSY_WAIT_SECONDS for connectionLock to free up, returning
	 * true holding it - or false, holding nothing, if the whole budget ran out
	 * first or "terminate" was requested by whoever currently holds it while
	 * this thread was still waiting. Polls in BUSY_WAIT_POLL_MILLIS steps
	 * (tryLock(long, TimeUnit), never the unbounded lock()) rather than one
	 * long blocking wait for the same reason run()'s own accept loop polls via
	 * serverSocket.setSoTimeout(1000) instead of blocking in accept(): a
	 * connection sitting here has to notice a shutdown requested in the
	 * meantime within about a second of connectionLock actually freeing up,
	 * not sleep past it for however much of the budget happened to be left -
	 * see BUSY_WAIT_POLL_MILLIS's own doc.
	 *
	 * The shutdown check runs between poll attempts, not before the first one:
	 * a connection already accepted by the time "terminate" lands is served
	 * exactly the same either way (see runSession()'s own
	 * isShutdownRequested() guard, which the very first command line is
	 * already subject to), so checking here first would only save, at most,
	 * one BUSY_WAIT_POLL_MILLIS-long tryLock() call - not worth a second,
	 * separate check.
	 */
	private boolean awaitConnectionSlot(final ClideContext context) {
		// A local deadline, not a field: this method runs concurrently on every
		// "clide-client" thread currently waiting (see acceptClient()), each with
		// its own budget - a shared field here would let one waiter's elapsed
		// time bleed into another's.
		final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(BUSY_WAIT_SECONDS);
		while (true) {
			try {
				if (connectionLock.tryLock(BUSY_WAIT_POLL_MILLIS, TimeUnit.MILLISECONDS))
					return true;
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}

			if (context.isShutdownRequested() || System.nanoTime() >= deadlineNanos)
				return false;
		}
	}

	/**
	 * Tells a client it cannot be served right now instead of leaving it to hang
	 * on a read that may never come back - see this class' own doc. Renders the
	 * same ?ERROR envelope every other refusal uses (ResultEnvelope, with no
	 * Command to ask for a body - the same shape runSession() already falls back
	 * to for UNKNOWN_KEYWORD), so a client parses this exactly like any other
	 * error rather than needing a special case just for it.
	 */
	private void rejectBusy(final Socket client) {
		final String runningNow = currentCommand;
		final String message = runningNow == null
				? "the daemon is already serving another client - try again shortly" + indexingNote()
				: "the daemon is busy running '" + runningNow + "' (started "
						+ TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - currentCommandStartedAtNanos)
						+ "s ago) - a rebuild can take up to 5 minutes, try again shortly" + indexingNote();

		try (Socket c = client; PrintStream out = new PrintStream(c.getOutputStream(), true, StandardCharsets.UTF_8)) {
			out.println(ResultEnvelope.render(CommandResult.error(ErrorCode.BUSY, message), ""));
		} catch (final IOException e) {
			// this client is already gone - nothing left to tell it
		}
	}

	/**
	 * Appended to a BUSY message when jdtls itself is the reason the connection
	 * holding connectionLock is taking so long: still indexing in the
	 * background (see JdtlsSession.isIndexingComplete()) rather than genuinely
	 * serving a second client. Empty once indexing has completed, or before
	 * session exists at all - a plain "try again shortly" is the whole story
	 * then. This is what tells apart "BUSY because someone else is really
	 * connected" from "BUSY because the one command actually running is stuck
	 * behind jdtls' own initial indexing" - see this class' own doc and
	 * JDTLS.md, section 4.
	 */
	private String indexingNote() {
		final JdtlsSession current = session;
		if (current == null || current.isIndexingComplete())
			return "";

		final String status = current.lastStatus();
		return " (jdtls is still indexing" + (status == null ? "" : ": " + status) + ")";
	}

	/**
	 * Serves one client until it disconnects. Its very first line only ever
	 * decides one thing now - see ConnectionMode: whether this connection carries
	 * a Lua script (handed straight to runScript()) or a stream of commands, read
	 * one by one below. Either way it is served in this daemon's own printMode
	 * (context.getPrintMode(), fixed at startup - see run()) - a connection no
	 * longer picks its own the way it could before "--human" became a daemon
	 * startup flag instead of a per-connection one (see Main, ClideDaemon's class
	 * doc).
	 */
	private void runSession(final BufferedReader reader, final PrintStream out, final ClideContext context)
			throws IOException {
		final PrintMode printMode = context.getPrintMode();

		// A --human daemon owes this connection a "> READY" prompt before every
		// command it reads - this one's very first included, same as every one
		// after it (see readCommandLine()). Without this, a person sitting at the
		// keyboard sees nothing at all after connecting and has no way to know the
		// daemon is already waiting on them - their first line only got read once
		// they'd blindly typed something (see printReadyPrompt()). An AI/script
		// connection still gets no preamble anywhere in this method - that is what
		// keeps a bare socket session, netcat included, working with no preamble,
		// see ConnectionMode's own doc.
		if (printMode == PrintMode.HUMAN)
			printReadyPrompt(out);

		final String firstLine = reader.readLine();
		if (firstLine == null)
			return; // this client disconnected without saying anything at all

		final ConnectionMode mode = ConnectionMode.of(firstLine);

		if (mode == ConnectionMode.SCRIPT) {
			runScript(reader, out, context);
			return; // a script connection carries one script and ends with it
		}

		// Only for a daemon started --human, and on every one of its connections
		// (not just the first) - see announceExternalChanges()'s own doc for why: a
		// still-open transaction may have something worth saying before this
		// connection's own first command is even read.
		if (printMode == PrintMode.HUMAN)
			announceExternalChanges(out, context);

		// A COMMANDS connection announces nothing (see ConnectionMode), so firstLine
		// is not a handshake but already this session's first command: it has to be
		// processed, not swallowed. carried holds it until the loop below consumes
		// it.
		String carried = mode.announced() ? null : firstLine;

		while (context.isShutdownRequested() == false) {
			final String line = carried != null ? carried : readCommandLine(reader, out, printMode);
			carried = null;
			if (line == null)
				return; // this client is done, the daemon keeps running for the next one

			final String keyword = line.trim();
			if (keyword.isEmpty())
				continue;

			final Command command = context.getCommand(keyword);
			if (command == null) {
				// The hint is diagnostic, not prescriptive: it says the error is
				// probably not the one it looks like. A whole command written on one
				// line is the mistake CLAUDE.md documents as the first one everybody
				// hits, and it surfaces here as a keyword nobody recognizes.
				printResult(out, null, CommandResult.error(ErrorCode.UNKNOWN_KEYWORD,
						"Unknown command '" + keyword + "'",
						"one token per line - a whole command written on a single line reads as one keyword"),
						printMode);
				continue;
			}

			if (printMode == PrintMode.HUMAN)
				if (command.paramSize() == 0)
					out.println("> Get '" + keyword + "'. No parameter expected");
				else
					out.println("> Get '" + keyword + "' expecting now " + command.paramSize() + " parameter(s).");

			final String[] params = readParams(reader, out, command, printMode);
			if (params == null) {
				printResult(out, command, CommandResult.error(ErrorCode.MISSING_PARAMETERS,
						"missing parameter(s) for " + keyword,
						"this connection is now closed - when piping a batch, finish it with exit"), printMode);
				return; // this client's input ended mid-command
			}

			// Every check that stands between a parameter and a command running lives
			// in CommandDispatcher, which the Lua bridge calls too - see its class doc
			// for why they cannot live here any more.
			//
			// currentCommand/currentCommandStartedAtNanos bracket exactly this call -
			// set just before, cleared right after - so a BUSY message another
			// connection's thread builds in the meantime (see rejectBusy()) names the
			// command actually running, not whichever one happened to run last; cleared
			// again (belt and suspenders) in serveOrRejectIfBusy()'s finally, in case
			// this method returns some other way first.
			currentCommand = keyword;
			currentCommandStartedAtNanos = System.nanoTime();
			final CommandResult result = CommandDispatcher.dispatch(context, command, out::println, params);
			currentCommand = null;
			printResult(out, command, result, printMode);
			if (context.isShutdownRequested() || context.isDisconnectRequested())
				return;
		}
	}

	/**
	 * Tells a HUMAN connection - never AI, never a script, see runSession()'s own
	 * callers - about any file a still-open transaction's own snapshot no longer
	 * matches.
	 *
	 * This is the normal way a transaction sees an edit today: with no
	 * file-modifying command of clide's own yet (see CLAUDE.md), the actual
	 * workflow is open_transaction, edit with other tools, then reconnect -
	 * possibly much later, possibly from a different clide invocation entirely -
	 * to inspect and commit_transaction or rollback_transaction. Without this, a
	 * human reconnecting would see nothing at all unless they remembered to type
	 * list_modified_files themselves first.
	 *
	 * HUMAN only, on purpose: AI mode's whole contract (see PrintMode and
	 * CLAUDE.md) is that it prints nothing but what the commands a client sent
	 * actually answer, so a machine client can assume a strict 1:1 correspondence
	 * between what it wrote and what it reads back. Printing this unasked would
	 * break that - worse, indistinguishably so if the client's own first command
	 * happens to be list_modified_files itself. An AI client that cares whether a
	 * transaction it reopens was touched from outside is expected to call
	 * list_modified_files itself right after reconnecting, the same way it would
	 * for anything else it wants to know.
	 *
	 * Reuses list_modified_files' own command - same executeCommand(), same
	 * render() - rather than reformatting the same information a second way: what
	 * a client sees here is byte-for-byte what typing the command itself would
	 * have printed for that id. Silent for a transaction with nothing to report,
	 * and silent altogether when nothing is open - the same "nothing at all
	 * printed when there is nothing to say" printResult() already follows for a
	 * command's own answer.
	 *
	 * Calling executeCommand() directly - not through CommandDispatcher, which
	 * every client-typed command goes through - skips CommandDispatcher's
	 * needsOpenTransaction()/needsJdtlsSession() gate on purpose: openIds() has
	 * already established a transaction is open, and list_modified_files itself
	 * needs no jdtls session, so nothing that gate would have caught is being
	 * bypassed here.
	 */
	private void announceExternalChanges(final PrintStream out, final ClideContext context) {
		final Command listModifiedFiles = context.getCommand("list_modified_files");
		for (final String id : context.getTransactions().openIds()) {
			final CommandResult result = listModifiedFiles.executeCommand(context, id);
			if (result.payload() instanceof CommandPayload.ModifiedFiles modified && modified.files().totalCount() == 0)
				continue;

			printResult(out, listModifiedFiles, result, PrintMode.HUMAN);
		}
	}

	/**
	 * Serves a connection that announced "--lua": everything after the handshake
	 * line is one Lua script, read whole and run here - see LuaBridge and LUA.md.
	 *
	 * Read to EOF rather than to a terminator, because a script is the entire rest
	 * of what this client has to say. The client (clide.py's relay(), see its own
	 * module doc) sends the file and half-closes its side, keeping the read
	 * direction open for what the script prints - so EOF arrives without the client
	 * having to invent a delimiter no line of Lua could ever collide with.
	 *
	 * The connection ends with the script, whether it succeeded or not. The daemon
	 * and its jdtls session stay up, exactly as after any other client hangs up:
	 * running a script is not a reason to pay for a workspace build again.
	 */
	private void runScript(final BufferedReader reader, final PrintStream out, final ClideContext context)
			throws IOException {
		final StringBuilder script = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null)
			script.append(line).append('\n');

		new LuaBridge(context, out).run(script.toString());
	}

	/**
	 * Prompts (HUMAN mode only) and reads the next command line, or null on this
	 * client's EOF.
	 */
	private String readCommandLine(final BufferedReader reader, final PrintStream out, final PrintMode printMode)
			throws IOException {
		if (printMode == PrintMode.HUMAN)
			printReadyPrompt(out);
		return reader.readLine();
	}

	/**
	 * The "> READY" prompt a --human daemon shows before it reads the next
	 * command line - this connection's very first one (see runSession()) and
	 * every one after it (see readCommandLine()) alike, so both print it exactly
	 * the same way.
	 */
	private void printReadyPrompt(final PrintStream out) {
		out.println();
		out.println("> READY");
	}

	/**
	 * Reads command.paramSize() parameters, one prompt per parameter, in the order
	 * command.getParamTypes() declares them. Every type but MULTI_LINE is read as a
	 * single trimmed line (see readSingleLineParam()); MULTI_LINE reads a whole
	 * block instead (see readMultiLineParam()) - a Java method body, or any other
	 * chunk of code a client wants to send as one parameter, doesn't fit the "one
	 * token per line" protocol's usual one-line-per-parameter rule (see CLAUDE.md).
	 * Returns null - the whole array, not just the offending entry - the moment any
	 * parameter (or, for MULTI_LINE, its terminator, or the block itself) hits EOF
	 * before being fully read: an incomplete command is not a command, whichever
	 * parameter it broke on.
	 */
	private String[] readParams(final BufferedReader reader, final PrintStream out, final Command command,
			final PrintMode printMode) throws IOException {
		final String[] comments = command.getDescriptionParam();
		final ParamType[] types = command.getParamTypes();
		final String[] params = new String[comments.length];
		for (int i = 0; i < comments.length; i++) {
			final String param = types[i] == ParamType.MULTI_LINE
					? readMultiLineParam(reader, out, comments[i], printMode)
					: readSingleLineParam(reader, out, comments[i], printMode);
			if (param == null)
				return null;

			params[i] = param;
		}
		return params;
	}

	/** Reads one line, prompted with comment, trimmed. Returns null on EOF. */
	private String readSingleLineParam(final BufferedReader reader, final PrintStream out, final String comment,
			final PrintMode printMode) throws IOException {
		if (printMode == PrintMode.HUMAN)
			out.println("> " + comment + " ?");
		final String line = reader.readLine();
		return line == null ? null : line.trim();
	}

	/**
	 * Reads a MULTI_LINE parameter (see ParamType.MULTI_LINE): first a single line,
	 * the terminator - any discriminant string the client picks, trimmed like any
	 * other single-line value but otherwise unvalidated - then every following
	 * line, kept verbatim (no trimming: indentation is part of the value, e.g. a
	 * tab-indented method body), until a line equal to that terminator is read.
	 * That line is consumed but excluded from the result; every line before it is
	 * joined with "\n" (no trailing "\n"; an empty block - the terminator on the
	 * very first line - returns ""). Returns null on EOF, whether it happens while
	 * reading the terminator itself or while reading the block that follows it.
	 */
	private String readMultiLineParam(final BufferedReader reader, final PrintStream out, final String comment,
			final PrintMode printMode) throws IOException {
		if (printMode == PrintMode.HUMAN)
			out.println("> " + comment + ": terminator (any string, ends the block) ?");
		final String terminatorLine = reader.readLine();
		if (terminatorLine == null)
			return null;

		final String terminator = terminatorLine.trim();
		if (printMode == PrintMode.HUMAN)
			out.println("> " + comment + " - one line at a time, '" + terminator + "' alone on a line to end ?");

		final StringBuilder block = new StringBuilder();
		while (true) {
			final String line = reader.readLine();
			if (line == null)
				return null;

			if (line.equals(terminator))
				return block.toString();

			if (block.length() > 0)
				block.append('\n');
			block.append(line);
		}
	}

	/**
	 * Renders result and writes it out - the one place a CommandResult becomes
	 * text.
	 *
	 * Two steps, deliberately separate. The body is the command's own business
	 * (Command.render(), which knows what its payload means); the envelope around
	 * it - the "?ERROR &lt;CODE&gt;" header, the hint, the warning lines - is the
	 * same for every command and belongs to ResultEnvelope. command is null only
	 * for the unknown-keyword case, where there is no command to ask and the
	 * envelope is the whole of the answer.
	 *
	 * Nothing at all is printed when there is nothing to say: a successful exit
	 * with no open transaction stays as silent as it always was.
	 */
	private void printResult(final PrintStream out, final Command command, final CommandResult result,
			final PrintMode printMode) {
		final String body = command == null ? "" : command.render(result, printMode);
		final String text = ResultEnvelope.render(result, body);
		if (text.isEmpty())
			return;

		out.println(text);
	}

	private void shutdown(final JdtlsSession session, final ServerSocket serverSocket) {
		session.stop();
		try {
			// The regular restore point now that run() no longer restores right after
			// the initial build (see run()'s own doc and EclipseProjectFiles' class
			// doc for why) - nothing is left watching the files by the time this runs,
			// so whatever jdtls does in reaction no longer matters. Also catches and
			// removes whatever jdtls wrote to .project on its own during the graceful
			// shutdown handshake session.stop() just ran, independently of anything
			// clide staged - see EclipseProjectFiles' class doc. Safe to run even for
			// a daemon whose run() already restored things once on a failed start (see
			// run()'s own finally): unstage() is idempotent per managed file.
			session.restoreEclipseFiles();
		} catch (final IOException e) {
			// best effort - a stray .project/.classpath is a cosmetic leftover, not
			// worth failing the shutdown over
		}
		DaemonLock.delete(projectRoot);
		try {
			serverSocket.close();
		} catch (final IOException e) {
			// already closed - nothing more to do
		}
	}

	/**
	 * Whether the project already has its Eclipse configuration files (.project and
	 * .classpath) at its root. When they are missing, jdtls generates both during
	 * the initial workspace import/build ("invisible project" support: source
	 * folders detected from the tree, every .clide/*.jar added as a library) -
	 * run() uses a before/after call to this method to report that generation in
	 * the startup trace, so a client seeing the project build correctly without any
	 * committed Eclipse files understands why.
	 */
	private boolean hasEclipseFiles() {
		return Files.isRegularFile(projectRoot.resolve(".project"))
				&& Files.isRegularFile(projectRoot.resolve(".classpath"));
	}

}
