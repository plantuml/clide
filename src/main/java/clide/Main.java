package clide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import clide.daemon.ClideClient;
import clide.daemon.ConnectionMode;

/**
 * Entry point for clide's client role: "clide &lt;project path&gt;" connects to
 * the daemon already running for that project if there is one, otherwise starts
 * one in the background first - see ClideClient. jdtls itself is only ever
 * started/built once per project this way, not on every clide run - see
 * ClideDaemon, which is the daemon's own separate entry point (an internal
 * re-exec ClideClient spawns; not meant to be typed by hand).
 *
 * "clide --human &lt;project path&gt;" opens that same session in HUMAN print mode
 * (a prompt between commands, and one per parameter being read) instead of the
 * default AI mode, which prints no prompt at all - see PrintMode. The flag
 * applies to this one session, never to the daemon it connects to.
 *
 * "clide --lua &lt;script&gt; &lt;project path&gt;" submits one Lua script to that
 * same daemon instead of opening an interactive session - see parseScriptPath()
 * and LUA.md. The Lua runtime itself lives in the daemon, not here: only there
 * do commands have a jdtls session and a project to answer about (see
 * LuaBridge). This process does for a script exactly what it does for a
 * keyboard - find or start the daemon, then relay - and the script is simply
 * what it relays.
 *
 * "clide --require-live-daemon &lt;project path&gt;" (combinable with either of the
 * above) refuses to silently start a fresh daemon in place of one that used to
 * be running for this project and has since stopped answering - see
 * ClideClient.REQUIRE_LIVE_DAEMON_FLAG and DaemonLock.State.DEAD. A project
 * that has never had a daemon still gets one started as usual: only a dead one
 * is refused, not an absent one.
 */
public class Main {

	public static final String VERSION = "0.0.1";

	/**
	 * What parseScriptPath() returns when there is no --lua flag at all, which is
	 * neither a script path nor a failure and so cannot be either null or a Path.
	 */
	private static final Path NOT_A_SCRIPT_RUN = Paths.get("");

	public static void main(final String[] args) throws IOException, InterruptedException {
		final boolean requireLiveDaemon = parseRequireLiveDaemon(args);

		final Path scriptPath = parseScriptPath(args);
		if (scriptPath == NOT_A_SCRIPT_RUN) {
			final PrintMode printMode = parsePrintMode(args);
			final Path projectRoot = parseProjectRoot(withoutRequireLiveDaemonFlag(withoutPrintModeFlag(args)));
			if (projectRoot == null)
				return;

			new ClideClient(projectRoot, printMode, requireLiveDaemon).run();
			return;
		}

		if (scriptPath == null)
			return; // the flag was there, what followed it was not - already said why

		if (parsePrintMode(args) == PrintMode.HUMAN) {
			// The two contradict each other: HUMAN's prompts exist for someone typing,
			// and a script reads none of them. Refused rather than quietly dropping
			// one of the two flags somebody deliberately wrote.
			System.out.println(
					"--human and " + ConnectionMode.SCRIPT_FLAG + " cannot be combined: a script reads no prompt");
			return;
		}

		final Path projectRoot = parseProjectRoot(withoutRequireLiveDaemonFlag(withoutScriptFlag(args)));
		if (projectRoot == null)
			return;

		new ClideClient(projectRoot, scriptPath, requireLiveDaemon).run();
	}

	/**
	 * The .lua file "clide --lua &lt;script&gt; &lt;project path&gt;" names;
	 * NOT_A_SCRIPT_RUN when no --lua flag appears at all, which is not an error but
	 * the ordinary case; null when the flag is there and what follows it is not
	 * usable (nothing at all, or not a readable file), the reason already printed.
	 *
	 * Unlike --human, this flag is not positional-free: it takes the argument
	 * immediately after it. A bare flag can be moved around because there is only
	 * one other argument to confuse it with - a flag that consumes the next one
	 * cannot.
	 */
	private static Path parseScriptPath(final String[] args) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals(ConnectionMode.SCRIPT_FLAG) == false)
				continue;

			if (i + 1 >= args.length) {
				System.out.println("Usage: clide " + ConnectionMode.SCRIPT_FLAG + " <script path> <project path>");
				return null;
			}

			final Path scriptPath = Paths.get(args[i + 1]).toAbsolutePath().normalize();
			if (Files.isRegularFile(scriptPath) == false) {
				System.out.println("Not a file: " + scriptPath);
				return null;
			}

			return scriptPath;
		}

		return NOT_A_SCRIPT_RUN;
	}

	/**
	 * args minus the --lua flag and the script path it consumed, leaving just the
	 * project path parseProjectRoot() counts.
	 */
	private static String[] withoutScriptFlag(final String[] args) {
		final List<String> kept = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals(ConnectionMode.SCRIPT_FLAG)) {
				i++; // and the script path with it
				continue;
			}

			kept.add(args[i]);
		}
		return kept.toArray(new String[0]);
	}

	/**
	 * HUMAN as soon as PrintMode.HUMAN_FLAG appears among args, AI otherwise -
	 * AI being the mode a session runs in unless it explicitly asks for the other
	 * one. The flag is positional-free on purpose: "clide --human &lt;project&gt;"
	 * and "clide &lt;project&gt; --human" both work, since there is only ever one
	 * other argument to confuse it with.
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
	 * True as soon as ClideClient.REQUIRE_LIVE_DAEMON_FLAG appears among args,
	 * false otherwise - false being the flag's absence, exactly as AI is
	 * PrintMode's. Positional-free for the same reason parsePrintMode() is: at
	 * most one other argument (--human/--lua notwithstanding) to confuse it
	 * with.
	 */
	private static boolean parseRequireLiveDaemon(final String[] args) {
		for (final String arg : args)
			if (arg.equals(ClideClient.REQUIRE_LIVE_DAEMON_FLAG))
				return true;

		return false;
	}

	/**
	 * args minus every ClideClient.REQUIRE_LIVE_DAEMON_FLAG occurrence - see
	 * withoutPrintModeFlag(), which this mirrors for the same reason: what's left
	 * is what parseProjectRoot() (or parseScriptPath()) expects to count.
	 */
	private static String[] withoutRequireLiveDaemonFlag(final String[] args) {
		return Arrays.stream(args).filter(arg -> arg.equals(ClideClient.REQUIRE_LIVE_DAEMON_FLAG) == false)
				.toArray(String[]::new);
	}

	/**
	 * Parses and validates the single "clide &lt;project path&gt;" argument shared
	 * by both of clide's entry points - this class (the client) and
	 * ClideDaemon.main() (the daemon, re-exec'd by ClideClient - see
	 * ClideClient.startDetachedDaemon()). Takes args with any print-mode flag
	 * already stripped (see withoutPrintModeFlag()). Prints a usage/error message
	 * and returns null if args is invalid; never throws.
	 */
	public static Path parseProjectRoot(final String[] args) {
		if (args.length != 1) {
			System.out.println(
					"Usage: clide [--human] [--lua <script path>] [--require-live-daemon] <project path>");
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
