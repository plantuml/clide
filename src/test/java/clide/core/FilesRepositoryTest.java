package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de FilesRepository.currentSourceFiles() - ce que le scan d'un projet
 * rend, maintenant qu'il lit et signe les fichiers sur plusieurs threads.
 *
 * Le parallélisme ne change rien de visible, et c'est précisément ce qu'il faut
 * vérifier : les mêmes fichiers, dans le même ordre, quel que soit le nombre de
 * threads qui les ont lus. L'ordre n'a pourtant aucune importance en aval
 * (Snapshot indexe par chemin, Delta trie) - mais un scan qui rendrait ses
 * fichiers dans un ordre différent à chaque exécution serait pénible à relire
 * dans un log, et surtout signalerait que chaque thread n'écrit pas dans sa
 * propre case.
 *
 * Les tests travaillent volontairement sur quelques centaines de fichiers, pas
 * deux ou trois : en dessous, tout tient dans une seule tâche et le
 * parallélisme n'est jamais exercé.
 */
class FilesRepositoryTest {

	private static final int MANY = 400;

	@Test
	@DisplayName("tous les .java du projet sont rendus, signés")
	void everySourceFileIsReturned(@TempDir final Path projectRoot) throws IOException {
		final List<Path> written = writeSources(projectRoot, MANY);

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(MANY, files.size());
		assertEquals(sorted(pathsOf(written)), sorted(pathsOf(files)));
		for (final SourceFile file : files)
			assertEquals(32, file.sourceFileMd5().length());
	}

	@Test
	@DisplayName("l'ordre rendu est celui du parcours, et il ne bouge pas d'une exécution à l'autre")
	void theOrderIsStableAcrossRuns(@TempDir final Path projectRoot) throws IOException {
		writeSources(projectRoot, MANY);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());

		final List<String> first = pathsOf(repository.currentSourceFiles());

		for (int run = 0; run < 5; run++)
			assertEquals(first, pathsOf(repository.currentSourceFiles()));

		assertEquals(walkOrder(projectRoot), first);
	}

	@Test
	@DisplayName("deux fichiers de même contenu restent deux entrées distinctes")
	void twoFilesWithTheSameContentAreTwoEntries(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve("Alpha.java"), "class X {}", StandardCharsets.UTF_8);
		Files.writeString(projectRoot.resolve("Beta.java"), "class X {}", StandardCharsets.UTF_8);

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(2, files.size());
	}

	@Test
	@DisplayName("les répertoires sans sources ne sont pas parcourus")
	void theSkippedDirectoriesAreNotWalked(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve("Kept.java"), "class Kept {}", StandardCharsets.UTF_8);
		for (final String skipped : List.of(".git", "build", "target", "node_modules", ".clide")) {
			final Path directory = Files.createDirectories(projectRoot.resolve(skipped));
			Files.writeString(directory.resolve("Hidden.java"), "class Hidden {}", StandardCharsets.UTF_8);
		}

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(1, files.size());
		assertTrue(files.iterator().next().sourceFilePath().endsWith("Kept.java"));
	}

	@Test
	@DisplayName("un package Java qui porte le nom d'un répertoire de build reste parcouru")
	void aJavaPackageNamedLikeABuildDirectoryIsStillWalked(@TempDir final Path projectRoot) throws IOException {
		// Le cas reel : clide lui-meme a un package clide.jdtls, et "jdtls" figurait
		// dans la liste des repertoires ignores. Tout src/main/java/clide/jdtls/
		// etait donc invisible - aucun Snapshot ne le voyait, donc rebuild ne voyait
		// pas ses modifications et une transaction ne le protegeait pas.
		for (final String name : List.of("jdtls", "build", "target", "bin", "out")) {
			final Path packageDirectory = Files.createDirectories(projectRoot.resolve("src/main/java/app/" + name));
			Files.writeString(packageDirectory.resolve("Kept.java"), "class Kept {}", StandardCharsets.UTF_8);
		}

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(5, files.size());
	}

	@Test
	@DisplayName("un répertoire de build reste ignoré à la racine du projet, lui")
	void aBuildDirectoryAtTheProjectRootIsStillSkipped(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve("Kept.java"), "class Kept {}", StandardCharsets.UTF_8);
		final Path generated = Files.createDirectories(projectRoot.resolve("build/classes/app"));
		Files.writeString(generated.resolve("Hidden.java"), "class Hidden {}", StandardCharsets.UTF_8);

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(1, files.size());
		assertTrue(files.iterator().next().sourceFilePath().endsWith("Kept.java"));
	}

	@Test
	@DisplayName("un .clide imbriqué reste ignoré : un point ne peut pas figurer dans un nom de package")
	void aNestedDotDirectoryIsStillSkippedAtAnyDepth(@TempDir final Path projectRoot) throws IOException {
		Files.writeString(projectRoot.resolve("Kept.java"), "class Kept {}", StandardCharsets.UTF_8);
		final Path nested = Files.createDirectories(projectRoot.resolve("modules/sub/.clide/tmp"));
		Files.writeString(nested.resolve("Hidden.java"), "class Hidden {}", StandardCharsets.UTF_8);

		final Set<SourceFile> files = new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles();

		assertEquals(1, files.size());
	}

	@Test
	@DisplayName("un projet sans aucune source rend un ensemble vide, sans échouer")
	void anEmptyProjectGivesAnEmptySet(@TempDir final Path projectRoot) throws IOException {
		assertTrue(new FilesRepository(projectRoot, Md5Repository.none()).currentSourceFiles().isEmpty());
	}

	@Test
	@DisplayName("le scan classe un blob par contenu, sans laisser de .tmp derrière lui")
	void theScanFilesOneBlobPerContent(@TempDir final Path projectRoot) throws IOException {
		writeSources(projectRoot, MANY);

		final Set<SourceFile> files = new FilesRepository(projectRoot, new Md5Repository(projectRoot))
				.currentSourceFiles();

		final List<Path> blobs = blobsOf(projectRoot);
		assertEquals(files.size(), blobs.size());
		assertTrue(blobs.stream().allMatch(blob -> blob.toString().endsWith(".gz")));
	}

	@Test
	@DisplayName("rescanner ne reclasse rien, et rend exactement le même résultat")
	void rescanningFilesNothingNew(@TempDir final Path projectRoot) throws IOException {
		writeSources(projectRoot, MANY);
		final FilesRepository repository = new FilesRepository(projectRoot, new Md5Repository(projectRoot));

		final Set<SourceFile> first = repository.currentSourceFiles();
		final List<Path> blobs = blobsOf(projectRoot);
		final Set<SourceFile> second = repository.currentSourceFiles();

		assertEquals(first, second);
		assertEquals(blobs, blobsOf(projectRoot));
	}

	@Test
	@DisplayName("un fichier modifié change sa signature, les autres gardent la leur")
	void onlyTheEditedFileChangesItsSignature(@TempDir final Path projectRoot) throws IOException {
		final List<Path> written = writeSources(projectRoot, MANY);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Set<SourceFile> before = repository.currentSourceFiles();

		Files.writeString(written.get(MANY / 2), "class Edited { int i; }", StandardCharsets.UTF_8);
		final Set<SourceFile> after = repository.currentSourceFiles();

		assertEquals(before.size(), after.size());
		final Set<SourceFile> moved = new LinkedHashSet<>(after);
		moved.removeAll(before);
		assertEquals(1, moved.size());
		assertTrue(moved.iterator().next().sourceFilePath().equals(written.get(MANY / 2).toString()));
		assertFalse(before.contains(moved.iterator().next()));
	}

	private List<Path> writeSources(final Path projectRoot, final int count) throws IOException {
		final List<Path> written = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final Path directory = Files.createDirectories(projectRoot.resolve("pack" + i % 7));
			final Path file = directory.resolve("Source" + i + ".java");
			Files.writeString(file, "class Source" + i + " { int i = " + i + "; }", StandardCharsets.UTF_8);
			written.add(file);
		}
		return written;
	}

	private List<String> pathsOf(final Set<SourceFile> files) {
		final List<String> paths = new ArrayList<>();
		for (final SourceFile file : files)
			paths.add(file.sourceFilePath());

		return paths;
	}

	private List<String> pathsOf(final List<Path> paths) {
		final List<String> asStrings = new ArrayList<>();
		for (final Path path : paths)
			asStrings.add(path.toString());

		return asStrings;
	}

	private List<String> sorted(final List<String> paths) {
		final List<String> copy = new ArrayList<>(paths);
		copy.sort(null);
		return copy;
	}

	private List<String> walkOrder(final Path projectRoot) throws IOException {
		try (Stream<Path> walk = Files.walk(projectRoot)) {
			return walk.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList();
		}
	}

	private List<Path> blobsOf(final Path projectRoot) throws IOException {
		final Path store = projectRoot.resolve(".clide").resolve("tmp").resolve("md5");
		if (Files.exists(store) == false)
			return List.of();
		try (Stream<Path> walk = Files.walk(store)) {
			return walk.filter(Files::isRegularFile).sorted().toList();
		}
	}

}
