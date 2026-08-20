package clide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import clide.daemon.ClideDaemon;
import clide.jdtls.LspClient.TimeoutException;

/**
 * Entry point for clide's daemon - clide.jar's only role now that the client
 * lives entirely in Python (see clide.py at the repository root, and
 * CLAUDE.md). There is no more "clide &lt;project&gt;" client mode in this
 * jar: starting the daemon is the one thing running it does.
 *
 * "java -jar clide.jar [--human] &lt;project path&gt;" starts the daemon for
 * that project in the foreground: it blocks, serving clients on a local TCP
 * port (see ClideDaemon, DaemonLock), until a connection sends "terminate".
 * Backgrounding it - {@code nohup ... &}, a systemd unit, a screen/tmux
 * session, whatever the caller prefers - is entirely the caller's job; clide
 * itself no longer forks or detaches on its own the way the previous
 * Java-client architecture's ClideClient used to (see HISTORY.md).
 *
 * The print mode - PrintMode.AI (default) or PrintMode.HUMAN, selected with
 * --human - is read once here and fixed for the whole lifetime of this
 * daemon process: every client that connects afterward - clide.py relaying a
 * keyboard, or relaying a --lua script - is served in that one mode. It
 * cannot be changed without restarting the daemon. (An earlier design let
 * each connection pick its own mode independently of the daemon's; see
 * HISTORY.md for that previous behavior.)
 *
 * If the daemon is not found running, clide.py fails with a message instead
 * of starting one - starting the daemon, explicitly, in the mode wanted, is
 * always this class's job now, never a side effect of connecting a client.
 */
public class Main {

	public static final String VERSION = "0.0.1";

	public static void main(final String[] args) throws IOException, InterruptedException, TimeoutException {
		final PrintMode printMode = parsePrintMode(args);
		final Path projectRoot = parseProjectRoot(withoutPrintModeFlag(args));
		if (projectRoot == null)
			return;

		new ClideDaemon(projectRoot, printMode, CommandRepository.commands).run();
	}

	/**
	 * HUMAN as soon as PrintMode.HUMAN_FLAG appears among args, AI otherwise - AI
	 * being the mode the daemon starts in unless it explicitly asks for the other
	 * one. The flag is positional-free on purpose: "java -jar clide.jar --human
	 * &lt;project&gt;" and "java -jar clide.jar &lt;project&gt; --human" both
	 * work, since there is only ever one other argument to confuse it with.
	 */
	public static PrintMode parsePrintMode(final String[] args) {
		for (final String arg : args)
			if (arg.equals(PrintMode.HUMAN_FLAG))
				return PrintMode.HUMAN;

		return PrintMode.AI;
	}

	/**
	 * args minus every PrintMode.HUMAN_FLAG occurrence, so what is left is just
	 * the project path parseProjectRoot() expects - it counts its arguments, and
	 * a flag still sitting in there would read as one argument too many.
	 */
	private static String[] withoutPrintModeFlag(final String[] args) {
		return Arrays.stream(args).filter(arg -> arg.equals(PrintMode.HUMAN_FLAG) == false).toArray(String[]::new);
	}

	/**
	 * Parses and validates the single "java -jar clide.jar [--human] &lt;project
	 * path&gt;" argument this entry point takes, with the print-mode flag already
	 * stripped (see withoutPrintModeFlag()). Prints a usage/error message and
	 * returns null if args is invalid; never throws.
	 */
	public static Path parseProjectRoot(final String[] args) {
		if (args.length != 1) {
			System.out.println("Usage: java -jar clide.jar [--human] <project path>");
			return null;
		}

		final Path projectRoot = Paths.get(args[0]).toAbsolutePath().normalize();
		if (Files.isDirectory(projectRoot) == false) {
			System.out.println("Not a directory: " + projectRoot);
			return null;
		}

		return projectRoot;
	}

}
