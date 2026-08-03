package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record SourceFile(String sourceFilePath, Long sourceFileTimestamp) {

	public static SourceFile fromPath(Path path) throws IOException {
		return new SourceFile(path.toString(), Files.getLastModifiedTime(path).toMillis());
	}

}
