package clide.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

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
 * Client connections are served one at a time (accept() loops sequentially):
 * jdtls itself only ever handles one request at a time anyway, and clide is a
 * single-user tool, so added concurrency here would buy nothing. A client
 * disconnecting (EOF on its socket, the normal end of a clide.py run) only
 * ends that connection - the daemon keeps running for the next one.
 * "exit"/"quit" (see DisconnectCommand) additionally stop the jdtls session
 * itself but still leave the daemon up - CommandDispatcher restarts it lazily
 * the next time a command actually needs it. Only "terminate" (see
 * TerminateCommand) shuts the whole daemon down.
 *
 * A connection announcing "--lua" is served differently: it carries one Lua
 * script rather than a stream of commands - see runScript() and ConnectionMode.
 */
public final class ClideDaemon {

	private final Path projectRoot;
	private final PrintMode printMode;
	private final Collection<Command> commands;

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
		final JdtlsLauncher launcher = new JdtlsLauncher(jdtlsHome);
		final Md5Repository md5Repository = new Md5Repository(projectRoot);
		final FilesRepository filesRepository = new FilesRepository(projectRoot, md5Repository);
		final JdtlsSession session = new JdtlsSession(launcher, filesRepository);
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
		DaemonLock.write(projectRoot, serverSocket.getLocalPort());
		Runtime.getRuntime()
				.addShutdownHook(new Thread(() -> shutdown(session, serverSocket), "clide-daemon-shutdown"));
		System.out.println("Daemon ready on port " + serverSocket.getLocalPort());

		while (context.isShutdownRequested() == false)
			serveOneClient(serverSocket, context);

		shutdown(session, serverSocket);
	}

	/**
	 * Handles a single client connection end to end. Returns on that client's own
	 * EOF, on "exit"/"quit" (this connection only), or on "terminate" (this
	 * connection, and the whole daemon via run()'s loop condition).
	 */
	private void serveOneClient(final ServerSocket serverSocket, final ClideContext context) throws IOException {
		// fresh connection: an earlier exit/quit must not leak into this one, and
		// neither must a max_results somebody else set - see ClideContext.
		context.resetPerConnectionSettings();
		try (Socket client = serverSocket.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				PrintStream out = new PrintStream(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
			runSession(reader, out, context);
		} catch (final IOException e) {
			// this client's connection broke - the daemon stays up for the next one
		}
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
			printResult(out, command, CommandDispatcher.dispatch(context, command, out::println, params), printMode);
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
