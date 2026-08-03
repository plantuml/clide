package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * One source file of a project as a snapshot sees it: where it is, and what is
 * in it - the md5 of its content, and nothing else.
 *
 * The mtime used to be held here too, and is deliberately gone: it answered
 * "when was this file written", which is not the question. The question is
 * "does this file still hold what it held at the last build", and the mtime is
 * wrong on it both ways - a build tool that rewrites a source untouched moves
 * the mtime with no change to report, and two edits within the same second on a
 * filesystem whose granularity is the second leave the mtime where it was, with
 * a change nobody reports. Reading the content settles it outright, and reading
 * both would only bring the first mistake back.
 *
 * Two instances are equal when they describe the same path with the same
 * content, which is exactly what Snapshot needs to decide that nothing moved.
 */
public final class SourceFile {

	private final String sourceFilePath;
	private final String sourceFileMd5;

	private SourceFile(final String sourceFilePath, final String sourceFileMd5) {
		this.sourceFilePath = sourceFilePath;
		this.sourceFileMd5 = sourceFileMd5;
	}

	public static SourceFile fromPath(final Path path) throws IOException {
		return new SourceFile(path.toString(), md5Of(path));
	}

	public String sourceFilePath() {
		return sourceFilePath;
	}

	public String sourceFileMd5() {
		return sourceFileMd5;
	}

	// The whole of what a snapshot compares. Costs roughly 3x a plain mtime
	// scan, cache-warm, on a ~3600 .java file project (measured on the PlantUML
	// checkout) - paid once per snapshot, against a jdtls rebuild avoided, and
	// against changes the mtime alone would have missed.
	private static String md5Of(final Path path) throws IOException {
		try {
			final MessageDigest digest = MessageDigest.getInstance("MD5");
			final byte[] hash = digest.digest(Files.readAllBytes(path));
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (final byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o)
			return true;
		if (o instanceof SourceFile == false)
			return false;
		final SourceFile other = (SourceFile) o;
		return Objects.equals(sourceFilePath, other.sourceFilePath)
				&& Objects.equals(sourceFileMd5, other.sourceFileMd5);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceFilePath, sourceFileMd5);
	}

	@Override
	public String toString() {
		return "SourceFile[sourceFilePath=" + sourceFilePath + ", sourceFileMd5=" + sourceFileMd5 + "]";
	}

}
