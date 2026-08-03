package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class FilesRepository {

	/**
	 * Directories currentSourceFiles() never walks into - no sources there, and on
	 * a project like PlantUML they hold far more files than the sources do.
	 */
	private static final List<String> SKIPPED_DIRECTORIES = List.of(".git", "bin", "build", "target", "out", "jdtls",
			"node_modules", ".gradle", ".clide");

	private final Path projectRoot;

	public FilesRepository(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public Path getProjectRoot() {
		return projectRoot;
	}

	public String projectUri() {
		final String uri = getProjectRoot().toAbsolutePath().toUri().toString();
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

	/**
	 * Absolute path -&gt; last-modified time of every .java file under the project,
	 * skipping the directories that hold no sources but do hold thousands of files
	 * (.git, build output, the extracted jdtls itself).
	 */
	public Set<SourceFile> currentSourceFiles() throws IOException {
		final Set<SourceFile> files = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(projectRoot)) {
			walk.filter(path -> path.toString().endsWith(".java")).filter(path -> isSkipped(path) == false)
					.forEach(path -> {
						try {
							files.add(SourceFile.fromPath(path));
						} catch (final IOException e) {
							// vanished between the walk and the stat - treat as absent
						}
					});
		}
		return files;
	}

	private boolean isSkipped(final Path path) {
		for (final Path segment : projectRoot.relativize(path))
			if (SKIPPED_DIRECTORIES.contains(segment.toString()))
				return true;

		return false;
	}

}
