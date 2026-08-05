package clide.jdtls;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Function;

/**
 * Decides where the extracted jdtls tree lives.
 *
 * It used to live at Paths.get("jdtls") - a *relative* path, resolved against
 * the daemon's working directory. Starting clide from a project therefore
 * dropped 62 MB of untracked files at that project's root, contradicting
 * CLAUDE.md's promise that a git status never moves because of clide, and made
 * every new working directory pay the extraction again (45 s cold, 77 s for the
 * second directory).
 *
 * Nothing about that tree is per-project or per-directory: it is read-only,
 * derived entirely from the bundled archive, and identical for every project
 * clide will ever open. So it belongs in a per-user cache, extracted once and
 * shared by every daemon - which is what resolve() returns, unless
 * CLIDE_JDTLS_HOME names somewhere else explicitly.
 *
 * The directory carries the archive's fingerprint in its name
 * (jdtls-3f2a1b7c, see JdtlsArchive.crc()), which is what makes a *persistent*
 * shared cache safe: a clide.jar rebuilt around a different jdtls resolves to a
 * different directory and extracts there, instead of silently reusing the tree
 * left by the previous one. Superseded directories are simply left behind -
 * inert, and removable by hand or by deleting the whole cache root.
 */
public final class JdtlsHome {

	/**
	 * Escape hatch: an absolute path to use verbatim, with no fingerprint suffix.
	 * Whoever sets it owns the freshness question - which is the point of an
	 * override.
	 */
	private static final String ENV_OVERRIDE = "CLIDE_JDTLS_HOME";

	private JdtlsHome() {
	}

	/**
	 * Where jdtls should be extracted to, and run from. Throws when the archive
	 * cannot be found at all (see JdtlsArchive.locate()): failing here, before the
	 * daemon has started anything, gives a far better message than failing halfway
	 * through the first extraction.
	 */
	public static Path resolve() throws IOException {
		final String override = System.getenv(ENV_OVERRIDE);
		if (override != null && override.isBlank() == false)
			return Paths.get(override).toAbsolutePath();

		return cacheRoot().resolve(directoryName(JdtlsArchive.locate().crc())).toAbsolutePath();
	}

	static String directoryName(final long crc) {
		return "jdtls-" + Long.toHexString(crc);
	}

	static Path cacheRoot() {
		return cacheRoot(System.getProperty("os.name", ""), Paths.get(System.getProperty("user.home", ".")),
				System::getenv);
	}

	/**
	 * The per-user cache directory, following each platform's own convention
	 * rather than imposing one: %LOCALAPPDATA%\clide on Windows (~/.cache is not a
	 * Windows notion, and %TEMP% gets swept), ~/Library/Caches/clide on macOS,
	 * $XDG_CACHE_HOME/clide or ~/.cache/clide elsewhere.
	 *
	 * Parameterized rather than reading the environment directly so the three
	 * branches are testable without touching the machine running the tests.
	 */
	static Path cacheRoot(final String osName, final Path userHome, final Function<String, String> env) {
		final String os = osName.toLowerCase(Locale.ROOT);

		if (os.contains("win")) {
			final String localAppData = env.apply("LOCALAPPDATA");
			if (localAppData != null && localAppData.isBlank() == false)
				return Paths.get(localAppData, "clide");

			return userHome.resolve("AppData").resolve("Local").resolve("clide");
		}

		if (os.contains("mac") || os.contains("darwin"))
			return userHome.resolve("Library").resolve("Caches").resolve("clide");

		final String xdgCacheHome = env.apply("XDG_CACHE_HOME");
		if (xdgCacheHome != null && xdgCacheHome.isBlank() == false)
			return Paths.get(xdgCacheHome, "clide");

		return userHome.resolve(".cache").resolve("clide");
	}
}
