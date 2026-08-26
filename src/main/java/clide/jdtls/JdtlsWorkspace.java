package clide.jdtls;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.CRC32;

/**
 * Decides where jdtls' own workspace - the directory Equinox is launched with
 * "-data" (see JdtlsLauncher.start()) - lives for a given project.
 *
 * It used to be {@code Files.createTempDirectory("clide-jdtls-data")}: a
 * fresh, empty, randomly-named directory every time a daemon started, even
 * for the same project twice in a row. jdtls treats "-data" the way Eclipse
 * treats a workspace: it is where the Java model it builds while indexing a
 * project - the resolved classpath, the type index over every dependency jar,
 * the compiled output of the last build - actually lives. An empty one means
 * none of that exists yet, so jdtls has to derive all of it from scratch
 * before it can answer anything - the whole reason a cold daemon start pays
 * a full project scan, on top of the "java/buildWorkspace" full recompile
 * JdtlsSession.build() always requests (see its own doc, and JDTLS.md's "Le
 * build incrémental est un piège" for why that recompile itself is not
 * shortened by any of this - jdtls is still asked to recompile every source
 * file, every time, on purpose). What a stable, reused workspace can spare a
 * second cold start on the *same* project is everything upstream of that
 * recompile: reimporting the "invisible project" (detecting source folders,
 * resolving every .clide/*.jar dependency) and rebuilding the type index over
 * those jars from nothing - see HISTORY.md/JDTLS.md for how much of a cold
 * start that scanning has been measured to cost on a project the size of
 * PlantUML.
 *
 * The directory carries the project's own fingerprint in its name (a CRC32 of
 * its absolute, normalized path - see fingerprint()), the same idea
 * JdtlsHome.resolve() already applies to the read-only jdtls distribution
 * itself, keyed there by the archive's fingerprint instead. Two different
 * projects therefore never share a workspace, and the same project always
 * resolves back to the same one, persisting under the shared per-user cache
 * (JdtlsHome.cacheRoot()) rather than under a temp directory some OS cleanup
 * policy might sweep between runs - the whole point being that it is still
 * there the next time this project's daemon starts.
 *
 * Escape hatch, same shape as JdtlsHome's CLIDE_JDTLS_HOME: a workspace is
 * ordinary Eclipse/OSGi state, not something clide can repair once it goes
 * bad (say, a daemon killed mid-write leaves a workspace jdtls itself refuses
 * to reopen). Nothing here detects or auto-recovers from that - deleting the
 * directory (by hand, or by pointing CLIDE_JDTLS_WORKSPACE) is the reset,
 * exactly as JdtlsHome documents for a superseded jdtls extraction.
 *
 * A reused directory only pays for itself if jdtls actually gets to leave it
 * in a clean state on the way out - see JdtlsLauncher.awaitExit()'s own doc
 * for a real failure mode this class' first version ran straight into:
 * stopping the process before its own graceful shutdown had finished saving
 * made every reopen pay a recovery pass more expensive than the cold import
 * this class exists to avoid.
 */
public final class JdtlsWorkspace {

	private static final String ENV_OVERRIDE = "CLIDE_JDTLS_WORKSPACE";

	private JdtlsWorkspace() {
	}

	/** Where jdtls should keep its workspace for projectRoot, across daemon restarts. */
	public static Path resolveFor(final Path projectRoot) {
		final String override = System.getenv(ENV_OVERRIDE);
		if (override != null && override.isBlank() == false)
			return Paths.get(override).toAbsolutePath();

		return JdtlsHome.cacheRoot().resolve(directoryName(projectRoot)).toAbsolutePath();
	}

	static String directoryName(final Path projectRoot) {
		return "workspace-" + Long.toHexString(fingerprint(projectRoot));
	}

	/**
	 * A CRC32 over the project's absolute, normalized path string - stable across
	 * repeated calls for the same path, on the same machine, which is all a cache
	 * key here needs to be (see JdtlsHome.crc()'s own fingerprint for the same
	 * reasoning applied to a file's bytes instead of a path's text). Not a promise
	 * that two different-looking paths naming the same directory (a symlink, a
	 * different case on a case-insensitive filesystem, a relative path resolved
	 * from a different working directory) always collide - only that the same
	 * Path, resolved the same way clide always resolves a project root (see
	 * Main.parseProjectRoot(): toAbsolutePath().normalize()), reliably does.
	 */
	static long fingerprint(final Path projectRoot) {
		final CRC32 crc = new CRC32();
		crc.update(projectRoot.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
		return crc.getValue();
	}

}
