package clide.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import clide.Main;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandRegistry;
import clide.core.CommandResult;
import clide.core.CommandStatus;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient.TimeoutException;

/**
 * The long-lived side of clide: owns the single JdtlsSession for a project and
 * a local TCP ServerSocket, and keeps both alive across many separate clide
 * invocations instead of paying jdtls' handshake and full workspace build
 * again every time - see CLAUDE.md. This is also the daemon's own entry point
 * (main() below) - ClideClient re-execs "java ... clide.daemon.ClideDaemon
 * &lt;project&gt;" as a detached background process the first time a project
 * is opened (see ClideClient.startDetachedDaemon()); not meant to be typed by
 * hand. Every later "clide &lt;project&gt;" run just connects to it as a
 * client - see ClideClient/Main.
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
	private final List<Command> commands;

	public ClideDaemon(final Path projectRoot, final List<Command> commands) {
		this.projectRoot = projectRoot;
		this.commands = commands;
	}

	/** Entry point for the daemon process itself - see the class doc above. */
	public static void main(final String[] args) throws IOException, InterruptedException, TimeoutException {
		final Path projectRoot = Main.parseProjectRoot(args);
		if (projectRoot == null)
			return;

		new ClideDaemon(projectRoot, Main.commands).run();
	}

	public void run() throws IOException, InterruptedException, TimeoutException {
		System.out.println("*** clide daemon starting for " + projectRoot);

		System.out.print("(1/3) Initializing IDE ...");
		final JdtlsLauncher launcher = new JdtlsLauncher(jdtlsHome());
		final JdtlsSession session = new JdtlsSession(launcher, projectRoot);
		System.out.println(" [OK]");

		System.out.print("(2/3) Starting session ...");
		session.start();
		System.out.println(" [OK]");

		System.out.print("(3/3) Building project ...");
		session.build();
		System.out.println(" [OK]");

		final CommandRegistry registry = new CommandRegistry(commands);
		final ClideContext context = new ClideContext(session, commands);

		final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		DaemonLock.write(projectRoot, serverSocket.getLocalPort());
		Runtime.getRuntime()
				.addShutdownHook(new Thread(() -> shutdown(session, serverSocket), "clide-daemon-shutdown"));
		System.out.println("Daemon ready on port " + serverSocket.getLocalPort());

		while (context.isShutdownRequested() == false)
			serveOneClient(serverSocket, registry, context);

		shutdown(session, serverSocket);
	}

	/**
	 * Handles a single client connection end to end. Returns on that client's own
	 * EOF, on "exit"/"quit" (this connection only), or on "terminate" (this
	 * connection, and the whole daemon via run()'s loop condition).
	 */
	private void serveOneClient(final ServerSocket serverSocket, final CommandRegistry registry,
			final ClideContext context) throws IOException {
		context.clearDisconnectRequested(); // fresh connection - an earlier exit/quit must not leak into this one
		try (Socket client = serverSocket.accept();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				PrintStream out = new PrintStream(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
			runSession(reader, out, registry, context);
		} catch (final IOException e) {
			// this client's connection broke - the daemon stays up for the next one
		}
	}

	private void runSession(final BufferedReader reader, final PrintStream out, final CommandRegistry registry,
			final ClideContext context) throws IOException {
		while (context.isShutdownRequested() == false) {
			out.println();
			out.println("> READY");
			final String line = reader.readLine();
			if (line == null)
				return; // this client is done, the daemon keeps running for the next one

			final String keyword = line.trim();
			if (keyword.isEmpty())
				continue;

			final Command command = registry.find(keyword);
			if (command == null) {
				out.println("?SYNTAX ERROR");
				continue;
			}

			if (command.paramSize() == 0)
				out.println("> Get '" + keyword + "'. No parameter expected");
			else
				out.println("> Get '" + keyword + "' expecting now " + command.paramSize() + " parameter(s).");

			final String[] params = readParams(reader, out, command.getDescriptionParam());
			if (params == null) {
				out.println("?SYNTAX ERROR: missing parameter(s) for " + keyword);
				return; // this client's input ended mid-command
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
	 * commands that declare needsJdtlsSession() - a cheap no-op (one boolean
	 * read) when the session is already up. Returns an error CommandResult if the
	 * restart itself fails, null if the session is ready to use.
	 */
	private CommandResult ensureSessionReady(final PrintStream out, final ClideContext context) {
		final JdtlsSession session = context.getCurrentSession();
		if (session.isReady())
			return null;

		out.println("jdtls session was stopped (exit/quit) - restarting it ...");
		try {
			session.start();
			session.build();
			return null;
		} catch (final Exception e) {
			return CommandResult.error("Failed to restart jdtls session: " + e.getMessage());
		}
	}

	/**
	 * Reads the next 'count' lines as parameters, one per line. Returns null if
	 * input ends before all of them are read.
	 */
	private String[] readParams(final BufferedReader reader, final PrintStream out, final String[] comments)
			throws IOException {
		final int size = comments.length;
		final String[] params = new String[size];
		for (int i = 0; i < size; i++) {
			out.println("> " + comments[i] + " ?");
			String paramLine = reader.readLine();
			if (paramLine == null)
				paramLine = "";

			params[i] = paramLine.trim();
		}
		return params;
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

}
