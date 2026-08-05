package clide.jdtls;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.zip.CRC32;

/**
 * The jdtls archive clide bootstraps its jdtls installation from: where to find
 * it, how to read it, and - the reason this is a class of its own rather than
 * two constants inside JdtlsLauncher - how to fingerprint it.
 *
 * The fingerprint (see crc()) is what lets JdtlsHome give every distinct
 * archive its own directory in a cache that is now shared and persistent. Before
 * that cache existed, an extraction was per-working-directory and effectively
 * disposable, so "jdtlsHome/plugins exists, nothing to do" was a good enough
 * freshness test; against a directory that survives every build of clide, it is
 * not - a clide.jar rebuilt around a newer jdtls would silently keep running the
 * old one. Naming the directory after the archive makes that impossible instead
 * of merely unlikely, and costs nothing: a jar entry carries its CRC in the
 * central directory, so the common case reads no bytes at all.
 *
 * The archive is looked up in three places, in this order:
 *
 * 1. ZIP_RESOURCE on the classpath - the "ant dist" fat-jar layout, where the
 *    archive is bundled inside clide.jar itself. First because it is the copy
 *    that ships with the very code doing the lookup: it cannot be out of step
 *    with it.
 * 2. ZIP_NAME next to whatever clide is running from (the jar's directory, or
 *    the classes directory for a plain "ant compile" run).
 * 3. ZIP_NAME in the current directory - a checkout run from its own root, the
 *    layout a fresh clone has before any dist is built.
 *
 * Note that none of these is the *destination* of the extraction: where jdtls
 * lands is JdtlsHome's decision, and deliberately unrelated to where the archive
 * was found.
 */
final class JdtlsArchive {

	/**
	 * Name of the vendored jdtls archive as committed at the root of the clide
	 * repository. Produced by scripts/download_and_zip_jdtls.py.
	 */
	static final String ZIP_NAME = "jdt-language-server-latest.zip";

	/**
	 * Classpath location of the same archive when bundled inside the jar - see
	 * "dist" in build.xml, which packs it under a top-level resource/ directory.
	 */
	static final String ZIP_RESOURCE = "/resource/" + ZIP_NAME;

	/** Non-null when the archive was found on the classpath; null otherwise. */
	private final URL resource;

	/** Non-null when the archive was found as a plain file; null otherwise. */
	private final Path file;

	private JdtlsArchive(final URL resource, final Path file) {
		this.resource = resource;
		this.file = file;
	}

	/**
	 * Finds the archive in the three places listed in this class' documentation,
	 * or fails naming all of them - this is the one error a user can actually act
	 * on ("you built a jar without the archive in it"), so it says what was looked
	 * for rather than only that something was missing.
	 */
	static JdtlsArchive locate() throws IOException {
		final URL resource = JdtlsArchive.class.getResource(ZIP_RESOURCE);
		if (resource != null)
			return new JdtlsArchive(resource, null);

		for (final Path candidate : diskCandidates())
			if (Files.isRegularFile(candidate))
				return new JdtlsArchive(null, candidate.toAbsolutePath());

		throw new IOException("jdtls is not installed: found neither the bundled " + ZIP_RESOURCE
				+ " classpath resource, nor " + ZIP_NAME + " next to the running clide"
				+ ", nor " + ZIP_NAME + " in the current directory");
	}

	/**
	 * An identifier of this exact archive, stable across runs and machines, used
	 * to name its extraction directory. The jar case reads the CRC straight out of
	 * the enclosing jar's central directory - no decompression, no I/O over the
	 * 49 MB payload; every other case pays one streaming pass (~50 ms), which only
	 * ever happens on a layout that has the archive loose on disk anyway.
	 */
	long crc() throws IOException {
		if (resource != null) {
			final URLConnection connection = resource.openConnection();
			if (connection instanceof JarURLConnection jarConnection) {
				final JarEntry entry = jarConnection.getJarEntry();
				if (entry != null && entry.getCrc() != -1)
					return entry.getCrc();
			}
		}
		return computeCrc();
	}

	/** Opens the archive for reading. The caller owns the stream. */
	InputStream open() throws IOException {
		if (resource != null)
			return new BufferedInputStream(resource.openStream());

		return new BufferedInputStream(Files.newInputStream(file));
	}

	/** Where this archive was found, for the daemon's startup trace. */
	String describe() {
		return resource != null ? resource.toString() : file.toString();
	}

	private long computeCrc() throws IOException {
		final CRC32 crc = new CRC32();
		final byte[] buffer = new byte[64 * 1024];
		try (InputStream in = open()) {
			int read;
			while ((read = in.read(buffer)) != -1)
				crc.update(buffer, 0, read);
		}
		return crc.getValue();
	}

	private static List<Path> diskCandidates() {
		final List<Path> candidates = new ArrayList<>();
		final Path codeSource = codeSourceDirectory();
		if (codeSource != null)
			candidates.add(codeSource.resolve(ZIP_NAME));

		candidates.add(Paths.get(ZIP_NAME));
		return candidates;
	}

	/**
	 * The directory clide itself is running from: the one holding clide.jar, or
	 * the classes directory of an exploded build. Null when the JVM does not say -
	 * a code source is absent under some class loaders, and its location is not
	 * always a file: URL.
	 */
	private static Path codeSourceDirectory() {
		try {
			final CodeSource source = JdtlsArchive.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null)
				return null;

			final Path location = Paths.get(source.getLocation().toURI());
			if (Files.isDirectory(location))
				return location;

			return location.getParent();
		} catch (final URISyntaxException | IllegalArgumentException notAFilePath) {
			return null;
		}
	}
}
