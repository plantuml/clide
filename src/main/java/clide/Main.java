package clide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import clide.daemon.ClideClient;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua51.Lua51;

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
 * A second entry point, "clide --lua &lt;script&gt; &lt;project path&gt;", runs a Lua
 * script through gudzpoz/luajava instead of starting an interactive session -
 * see runLuaScript() and LUA.md. POC stage: the script runs standalone, with
 * no clide command bound into Lua yet.
 */
public class Main {

	public static final String VERSION = "0.0.1";

	public static void main(final String[] args) throws IOException, InterruptedException {
		if (args.length > 0 && args[0].equals("--lua")) {
			runLuaScript(args);
			return;
		}

		final PrintMode printMode = parsePrintMode(args);
		final Path projectRoot = parseProjectRoot(withoutPrintModeFlag(args));
		if (projectRoot == null)
			return;

		new ClideClient(projectRoot, printMode).run();
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
	 * Parses and validates the single "clide &lt;project path&gt;" argument shared
	 * by both of clide's entry points - this class (the client) and
	 * ClideDaemon.main() (the daemon, re-exec'd by ClideClient - see
	 * ClideClient.startDetachedDaemon()). Takes args with any print-mode flag
	 * already stripped (see withoutPrintModeFlag()). Prints a usage/error message
	 * and returns null if args is invalid; never throws.
	 */
	public static Path parseProjectRoot(final String[] args) {
		if (args.length != 1) {
			System.out.println("Usage: clide [--human] <project path>");
			return null;
		}

		final Path projectRoot = Paths.get(args[0]).toAbsolutePath().normalize();
		if (Files.isDirectory(projectRoot) == false) {
			System.out.println("Not a directory: " + projectRoot);
			return null;
		}

		return projectRoot;
	}

	/**
	 * "clide --lua &lt;script&gt; &lt;project path&gt;": reads the Lua script file whole
	 * and runs it through party.iroiro.luajava's native Lua 5.1 backend
	 * (Lua51). The project path is parsed and validated the same way as the
	 * interactive entry point even though the POC script does not use it yet
	 * - keeping both entry points consistent about what "a valid project
	 * path" means avoids a divergence later once Lua scripts do call into
	 * clide commands (see LUA.md).
	 */
	private static void runLuaScript(final String[] args) throws IOException {
		if (args.length != 3) {
			System.out.println("Usage: clide --lua <script path> <project path>");
			return;
		}

		final Path scriptPath = Paths.get(args[1]);
		if (Files.isRegularFile(scriptPath) == false) {
			System.out.println("Not a file: " + scriptPath);
			return;
		}

		final Path projectRoot = Paths.get(args[2]).toAbsolutePath().normalize();
		if (Files.isDirectory(projectRoot) == false) {
			System.out.println("Not a directory: " + projectRoot);
			return;
		}

		final String script = Files.readString(scriptPath);
		try (Lua lua = new Lua51()) {
			lua.run(script);
		} catch (final LuaException error) {
			System.out.println("Lua error: " + error.getMessage());
		}
	}

}
