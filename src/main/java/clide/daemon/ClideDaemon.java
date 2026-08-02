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
import java.nio.file.Paths;
import java.util.Collection;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import clide.Main;
import clide.PrintMode;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.core.CommandStatus;
import clide.core.Position;
import clide.core.TransactionStack;
import clide.jdtls.EclipseProjectFiles;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient.TimeoutException;

/**
 * The long-lived side of clide: owns the single JdtlsSession for a project and
 * a local TCP ServerSocket, and keeps both alive across many separate clide
 * invocations instead of paying jdtls' handshake and full workspace build again
 * every time - see CLAUDE.md. This is also the daemon's own entry point (main()
 * below) - ClideClient re-execs "java ... clide.daemon.ClideDaemon
 * &lt;project&gt;" as a detached background process the first time a project is
 * opened (see ClideClient.startDetachedDaemon()); not meant to be typed by
 * hand. Every later "clide &lt;project&gt;" run just connects to it as a client
 * - see ClideClient/Main.
 *
 * Client connections are served one at a time (accept() loops sequentially):
 * jdtls itself only ever handles one request at a time anyway, and clide is a
 * single-user tool, so added concurrency here would buy nothing. A client
 * disconnecting (EOF on its socket, the normal end of a "clide" run) only ends
 * that connection - the daemon keeps running for the next one. "exit"/"quit"
 * (see DisconnectCommand) additionally stop the jdtls session itself but still
 * leave the daemon up - see ensureSessionReady(), which restarts it lazily the
 * next time a command actually needs it. Only "terminate" (see
 * TerminateCommand) shuts the whole daemon down.
 */
public final class ClideDaemon {

	private final Path projectRoot;
	private final Collection<Command> commands;
	private final PrintMode printMode;

	public ClideDaemon(final Path projectRoot, Collection<Command> commands, PrintMode printMode) {
		this.projectRoot = projectRoot;
		this.commands = commands;
		this.printMode = printMode;
	}

	/** Entry point for the daemon process itself - see the class doc above. */
	public static void main(final String[] args) throws IOException, InterruptedException, TimeoutException {
		final Path projectRoot = Main.parseProjectRoot(args);
		if (projectRoot == null)
			return;

		new ClideDaemon(projectRoot, Main.commands, Main.printMode).run();
	}

	public void run() throws IOException, InterruptedException, TimeoutException {
		System.out.println("*** clide daemon starting for " + projectRoot);

		System.out.print("(1/4) Checking for a leftover transaction state ...");
		TransactionStack.refuseIfDirty(projectRoot);
		EclipseProjectFiles.refuseIfDirty(projectRoot);
		System.out.println(" [OK]");

		final boolean eclipseFilesWereMissing = hasEclipseFiles() == false;

		System.out.print("(2/4) Initializing IDE ...");
		final JdtlsLauncher launcher = new JdtlsLauncher(jdtlsHome());
		final JdtlsSession session = new JdtlsSession(launcher, projectRoot);
		System.out.println(" [OK]");

		System.out.print("(3/4) Starting session ...");
		// start()+build() together in one try/finally: whatever happens - both
		// succeed, or either throws - restoreEclipseFiles() must run before this
		// method returns or propagates, so a project's own .project/.classpath (if
		// any) is never left stranded in .clide/tmp/ - see EclipseProjectFiles.
		try {
			session.start();
			System.out.println(" [OK]");

			System.out.print("(4/4) Building project ...");
			session.build();
		} finally {
			session.restoreEclipseFiles();
		}

		if (eclipseFilesWereMissing)
			System.out.println(
					" [OK] (imported via a temporary .project/.classpath from src/**/java and .clide/*.jar, "
							+ "removed afterward - none existed before)");
		else
			System.out.println(" [OK] (imported via a temporary .project/.classpath, "
					+ "the project's own restored afterward - see .clide/tmp/ for what was actually used)");

		final ClideContext context = new ClideContext(projectRoot, session, commands);

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
		context.clearDisconnectRequested(); // fresh connection - an earlier exit/quit must not leak into this one
		try (Socket client = serverSocket.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				PrintStream out = new PrintStream(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
			runSession(reader, out, context);
		} catch (final IOException e) {
			// this client's connection broke - the daemon stays up for the next one
		}
	}

	private void runSession(final BufferedReader reader, final PrintStream out, final ClideContext context)
			throws IOException {
		while (context.isShutdownRequested() == false) {
			if (printMode == PrintMode.HUMAN) {
				out.println();
				out.println("> READY");
			}
			final String line = reader.readLine();
			if (line == null)
				return; // this client is done, the daemon keeps running for the next one

			final String keyword = line.trim();
			if (keyword.isEmpty())
				continue;

			final Command command = context.getCommand(keyword);
			if (command == null) {
				out.println("?SYNTAX ERROR");
				continue;
			}

			if (printMode == PrintMode.HUMAN)
				if (command.paramSize() == 0)
					out.println("> Get '" + keyword + "'. No parameter expected");
				else
					out.println("> Get '" + keyword + "' expecting now " + command.paramSize() + " parameter(s).");

			final String[] params = readParams(reader, out, command);
			if (params == null) {
				out.println("?SYNTAX ERROR: missing parameter(s) for " + keyword);
				return; // this client's input ended mid-command
			}

			final String paramError = validateParams(command, params, context.getProjectRoot());
			if (paramError != null) {
				out.println("?SYNTAX ERROR: " + paramError);
				continue; // surface-invalid parameter - back to READY, the command never runs
			}

			if (command.needsOpenTransaction() && context.getTransactions().hasAnyOpen() == false) {
				printResult(out, CommandResult.error(keyword + " requires an open transaction - see open_transaction"));
				continue;
			}

			if (command.needsJdtlsSession()) {
				final CommandResult restartFailure = ensureSessionReady(out, context);
				if (restartFailure != null) {
					printResult(out, restartFailure);
					continue;
				}
			}

			printResult(out, command.executeCommand(context, params));
			if (context.isShutdownRequested() || context.isDisconnectRequested())
				return;
		}
	}

	/**
	 * Lazily restarts the jdtls session if a previous "exit"/"quit" stopped it
	 * while leaving the daemon (and this connection) running. Only called for
	 * commands that declare needsJdtlsSession() - a cheap no-op (one boolean read)
	 * when the session is already up. Returns an error CommandResult if the restart
	 * itself fails, null if the session is ready to use.
	 */
	private CommandResult ensureSessionReady(final PrintStream out, final ClideContext context) {
		final JdtlsSession session = context.getCurrentSession();
		if (session.isReady())
			return null;

		out.println("jdtls session was stopped (exit/quit) - restarting it ...");
		try {
			// Same try/finally shape as run()'s initial start+build, and for the same
			// reason: restoreEclipseFiles() must run whichever of the two throws, or
			// not, so a re-staged .project/.classpath never outlives this restart.
			try {
				session.start();
				session.build();
			} finally {
				session.restoreEclipseFiles();
			}
			return null;
		} catch (final Exception e) {
			return CommandResult.error("Failed to restart jdtls session: " + e.getMessage());
		}
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
	private String[] readParams(final BufferedReader reader, final PrintStream out, final Command command)
			throws IOException {
		final String[] comments = command.getDescriptionParam();
		final ParamType[] types = command.getParamTypes();
		final String[] params = new String[comments.length];
		for (int i = 0; i < comments.length; i++) {
			final String param = types[i] == ParamType.MULTI_LINE ? readMultiLineParam(reader, out, comments[i])
					: readSingleLineParam(reader, out, comments[i]);
			if (param == null)
				return null;

			params[i] = param;
		}
		return params;
	}

	/** Reads one line, prompted with comment, trimmed. Returns null on EOF. */
	private String readSingleLineParam(final BufferedReader reader, final PrintStream out, final String comment)
			throws IOException {
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
	private String readMultiLineParam(final BufferedReader reader, final PrintStream out, final String comment)
			throws IOException {
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
	 * Runs validate() over every parameter, in order, before the command they
	 * belong to ever executes - the "surface" check ParamType.POSITION/REGEX
	 * exist for (see CLAUDE.md, ParamType). Returns the first error message
	 * found, or null once every parameter has passed.
	 */
	private String validateParams(final Command command, final String[] params, final Path projectRoot) {
		final ParamType[] types = command.getParamTypes();
		for (int i = 0; i < params.length; i++) {
			final String error = validate(types[i], params[i], projectRoot);
			if (error != null)
				return error;
		}
		return null;
	}

	/**
	 * Surface-level check for one parameter's raw text, run purely on that text -
	 * TRANSACTION_ID must match TransactionStack.ID_PATTERN, REGEX must compile
	 * (java.util.regex.Pattern), POSITION must parse as a real file/line/word
	 * (see Position.parse()). Every other ParamType has nothing to check here.
	 * Returns null when value is acceptable, or an error message fit to send
	 * back to the client as-is otherwise.
	 */
	private String validate(final ParamType type, final String value, final Path projectRoot) {
		switch (type) {
		case TRANSACTION_ID:
			if (TransactionStack.ID_PATTERN.matcher(value).matches() == false)
				return "Invalid transaction id '" + value + "' - expected $segment, lowercase word characters only "
						+ "(e.g. $refactor_foo, $refactor_foo$part1)";
			return null;
		case REGEX:
			try {
				Pattern.compile(value);
			} catch (final PatternSyntaxException e) {
				return "Invalid regex '" + value + "': " + e.getMessage();
			}
			return null;
		case POSITION:
			try {
				Position.parse(value, projectRoot);
			} catch (final IllegalArgumentException e) {
				return e.getMessage();
			}
			return null;
		default:
			return null;
		}
	}

	private void printResult(final PrintStream out, final CommandResult result) {
		if (result.message().isEmpty())
			return;

		if (result.status() == CommandStatus.ERROR)
			out.println("Error: " + result.message());
		else
			out.println(result.message());
	}

	private void shutdown(final JdtlsSession session, final ServerSocket serverSocket) {
		session.stop();
		try {
			// jdtls can write .project back on its own during the graceful shutdown
			// handshake session.stop() just ran, independently of anything clide staged
			// - see EclipseProjectFiles' class doc. Running the same cleanup again here
			// catches and removes that, now that nothing is left watching the files.
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

	private Path jdtlsHome() {
		final String override = System.getenv("CLIDE_JDTLS_HOME");
		if (override != null)
			return Paths.get(override);

		return Paths.get("jdtls");
	}

	/**
	 * Whether the project already has its Eclipse configuration files (.project
	 * and .classpath) at its root. When they are missing, jdtls generates both
	 * during the initial workspace import/build ("invisible project" support:
	 * source folders detected from the tree, every .clide/*.jar added as a
	 * library) - run() uses a before/after call to this method to report that
	 * generation in the startup trace, so a client seeing the project build
	 * correctly without any committed Eclipse files understands why.
	 */
	private boolean hasEclipseFiles() {
		return Files.isRegularFile(projectRoot.resolve(".project"))
				&& Files.isRegularFile(projectRoot.resolve(".classpath"));
	}

}
