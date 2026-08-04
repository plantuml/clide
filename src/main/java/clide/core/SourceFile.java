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
 * Two instances are equal when they describe the same path with the same
 * content, which is exactly what Snapshot needs to decide that nothing moved.
 */
public final class SourceFile {

	private final String sourceFilePath;
	private final String sourceFileMd5;

	private SourceFile(Md5Repository md5Repository, final Path path) throws IOException {
		this.sourceFilePath = path.toString();
		if (md5Repository == null)
			this.sourceFileMd5 = Md5Repository.md5Of(path);
		else
			this.sourceFileMd5 = md5Repository.register(path);
	}

	public static SourceFile fromPath(Md5Repository md5Repository, final Path path) throws IOException {
		return new SourceFile(md5Repository, path);
	}

	public String sourceFilePath() {
		return sourceFilePath;
	}

	public String sourceFileMd5() {
		return sourceFileMd5;
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
