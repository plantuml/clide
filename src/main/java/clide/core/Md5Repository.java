package clide.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * The content-addressed store behind every snapshot: the md5 of a source file
 * is both the signature a Snapshot compares and the address its content is
 * filed under, in
 * <code>.clide/tmp/md5/&lt;two first characters&gt;/&lt;md5&gt;.gz</code>.
 *
 * Two properties are what the transactions to come will rely on. Filing is
 * idempotent - the same content is written once and never rewritten, so a
 * project walked a second time pays for reading and hashing, never for
 * writing. And nothing is ever overwritten in place: a blob is written to a
 * temporary file and renamed, so a reader sees it whole or not at all.
 *
 * none() is the repository that files nothing - it computes signatures without
 * a project root, for the tests and for any caller that only wants to compare.
 */
public class Md5Repository {

	private final Path projectRoot;

	public Md5Repository(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public static Md5Repository none() {
		return new Md5Repository(null);
	}

	public Path getProjectRoot() {
		return projectRoot;
	}

	// The whole of what a snapshot compares. Costs roughly 3x a plain mtime
	// scan, cache-warm, on a ~3600 .java file project (measured on the PlantUML
	// checkout) - paid once per snapshot, against a jdtls rebuild avoided, and
	// against changes the mtime alone would have missed.
	public static String md5Of(final Path path) throws IOException {
		return md5Of(Files.readAllBytes(path));
	}

	private static String md5Of(final byte[] content) throws IOException {
		try {
			final MessageDigest digest = MessageDigest.getInstance("MD5");
			final byte[] hash = digest.digest(content);
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (final byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	/**
	 * The signature of what is in <code>path</code> right now, its content filed
	 * under that signature on the way - unless it is already filed, which is the
	 * common case as soon as a project has been walked once, or unless this
	 * repository has no root (see none()).
	 *
	 * The file is read once, not twice: the bytes hashed are the bytes filed.
	 */
	public String register(final Path path) throws IOException {
		final byte[] content = Files.readAllBytes(path);
		final String md5 = md5Of(content);
		if (projectRoot == null)
			return md5;

		storeBlob(md5, content);
		return md5;
	}

	/**
	 * Where the content signed <code>md5</code> lives. The two first characters
	 * become a directory of their own: 256 buckets, so that a project of a few
	 * thousand files - times the successive revisions a day of editing produces -
	 * never piles tens of thousands of entries into one single directory.
	 */
	Path blobPath(final String md5) {
		return projectRoot.resolve(".clide").resolve("tmp").resolve("md5").resolve(md5.substring(0, 2))
				.resolve(md5 + ".gz");
	}

	/**
	 * Files content under its signature, gzipped at BEST_SPEED: this store is a
	 * scratch area rewritten all day long, so what counts is the time to write,
	 * not the last few percent of size. On the PlantUML checkout (3633 .java
	 * files, 16 MB) level 1 compresses to 5.7 MB in 275 ms where level 6 gives
	 * 5.3 MB in 404 ms: 8% smaller for 1.5x the compression time - and both are
	 * dwarfed by the ~2 s of creating 3633 files in the first place.
	 *
	 * Already filed means nothing to do: same md5, same bytes.
	 */
	private void storeBlob(final String md5, final byte[] content) throws IOException {
		final Path target = blobPath(md5);
		if (Files.exists(target))
			return;

		Files.createDirectories(target.getParent());
		final Path temporary = target
				.resolveSibling(md5 + "." + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId()
						+ ".tmp");
		try {
			try (OutputStream out = fastGzipTo(temporary)) {
				out.write(content);
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (final FileAlreadyExistsException e) {
			// another thread, or another clide, filed the same content first -
			// same md5 means same bytes, so theirs is ours
			Files.deleteIfExists(temporary);
		} catch (final IOException e) {
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	private static OutputStream fastGzipTo(final Path path) throws IOException {
		return new GZIPOutputStream(Files.newOutputStream(path), 16 * 1024) {
			{
				def.setLevel(Deflater.BEST_SPEED);
			}
		};
	}

}
