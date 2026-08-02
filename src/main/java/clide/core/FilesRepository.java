package clide.core;

import java.nio.file.Path;

public class FilesRepository {

	private final Path projectRoot;

	public FilesRepository(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public Path getProjectRoot() {
		return projectRoot;
	}

}
