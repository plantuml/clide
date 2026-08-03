package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class SourceFile {

	private final String sourceFilePath;
	private final Long sourceFileTimestamp;
	private final String sourceFileMd5;

	private SourceFile(final String sourceFilePath, final Long sourceFileTimestamp, final String sourceFileMd5) {
		this.sourceFilePath = sourceFilePath;
		this.sourceFileTimestamp = sourceFileTimestamp;
		this.sourceFileMd5 = sourceFileMd5;
	}

	public static SourceFile fromPath(final Path path) throws IOException {
		return new SourceFile(path.toString(), Files.getLastModifiedTime(path).toMillis(), md5Of(path));
	}

	public String sourceFilePath() {
		return sourceFilePath;
	}

	public Long sourceFileTimestamp() {
		return sourceFileTimestamp;
	}

	public String sourceFileMd5() {
		return sourceFileMd5;
	}

	// Not used yet - added to measure the cost of hashing file contents on top
	// of the plain mtime scan (see perf test on the PlantUML checkout: roughly
	// 3x the scan time, cache-warm, on a ~3600 .java file project).
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
				&& Objects.equals(sourceFileTimestamp, other.sourceFileTimestamp)
				&& Objects.equals(sourceFileMd5, other.sourceFileMd5);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceFilePath, sourceFileTimestamp, sourceFileMd5);
	}

	@Override
	public String toString() {
		return "SourceFile[sourceFilePath=" + sourceFilePath + ", sourceFileTimestamp=" + sourceFileTimestamp
				+ ", sourceFileMd5=" + sourceFileMd5 + "]";
	}

}
