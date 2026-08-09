package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de Transaction : ce qu'un Snapshot pris à l'ouverture permet de dire
 * (et de restaurer) sur un fichier, sans jamais garder de copie de ses octets
 * ailleurs que dans le dépôt content-addressed de Md5Repository.
 *
 * Contrairement à SnapshotTest, ces tests construisent leur FilesRepository
 * avec un vrai Md5Repository (jamais none()) : c'est justement le classement
 * des blobs pendant Snapshot.build() que la restauration exploite ensuite -
 * un dépôt none() ne filerait rien, et restoreFile()/restoreAll() n'auraient
 * plus rien à relire.
 */
class TransactionTest {

	private Transaction open(final Path projectRoot) throws IOException {
		final FilesRepository filesRepository = new FilesRepository(projectRoot, new Md5Repository(projectRoot));
		return new Transaction("$test", projectRoot.resolve(".clide/transactions/$test"), filesRepository);
	}

	@Test
	@DisplayName("un fichier jamais touché depuis l'ouverture n'a pas de sauvegarde")
	void untouchedFileHasNoBackup(@TempDir final Path root) throws IOException {
		write(root, "Alpha.java", "class Alpha {}");
		final Transaction transaction = open(root);

		assertFalse(transaction.hasBackup("Alpha.java"));
	}

	@Test
	@DisplayName("un fichier modifié après l'ouverture a une sauvegarde")
	void changedFileHasABackup(@TempDir final Path root) throws IOException {
		write(root, "Alpha.java", "class Alpha {}");
		final Transaction transaction = open(root);

		write(root, "Alpha.java", "class Alpha { int i; }");

		assertTrue(transaction.hasBackup("Alpha.java"));
	}

	@Test
	@DisplayName("beforeLines() rend le contenu tel qu'il était à l'ouverture")
	void beforeLinesReturnsThePreTransactionContent(@TempDir final Path root) throws IOException {
		write(root, "Alpha.java", "class Alpha {", "}");
		final Transaction transaction = open(root);

		write(root, "Alpha.java", "class Alpha {", "  int i;", "}");

		assertEquals(List.of("class Alpha {", "}"), transaction.beforeLines("Alpha.java"));
	}

	@Test
	@DisplayName("un fichier créé après l'ouverture a une sauvegarde, avec un beforeLines() vide")
	void createdFileHasAnEmptyBefore(@TempDir final Path root) throws IOException {
		final Transaction transaction = open(root);

		write(root, "Alpha.java", "class Alpha {}");

		assertTrue(transaction.hasBackup("Alpha.java"));
		assertEquals(List.of(), transaction.beforeLines("Alpha.java"));
	}

	@Test
	@DisplayName("restoreFile() supprime un fichier créé après l'ouverture")
	void restoreFileDeletesACreatedFile(@TempDir final Path root) throws IOException {
		final Transaction transaction = open(root);
		final Path created = write(root, "Alpha.java", "class Alpha {}");

		transaction.restoreFile("Alpha.java");

		assertFalse(Files.exists(created));
	}

	@Test
	@DisplayName("restoreFile() rend à un fichier modifié ses octets d'origine, exactement")
	void restoreFileRestoresTheExactOriginalBytes(@TempDir final Path root) throws IOException {
		final byte[] original = "class Alpha {\r\n}\r\n".getBytes(StandardCharsets.UTF_8);
		final Path file = root.resolve("Alpha.java");
		Files.write(file, original);
		final Transaction transaction = open(root);

		Files.writeString(file, "class Alpha { int i; }", StandardCharsets.UTF_8);
		transaction.restoreFile("Alpha.java");

		// Les fins de ligne CRLF d'origine doivent survivre au restore : un aller-
		// retour par Files.readAllLines()/Files.write(List) les aurait effacées.
		assertTrue(Arrays.equals(original, Files.readAllBytes(file)));
	}

	@Test
	@DisplayName("restoreFile() recrée un fichier supprimé après l'ouverture")
	void restoreFileRecreatesADeletedFile(@TempDir final Path root) throws IOException {
		write(root, "Alpha.java", "class Alpha {}");
		final Transaction transaction = open(root);
		final Path file = root.resolve("Alpha.java");
		Files.delete(file);

		transaction.restoreFile("Alpha.java");

		assertEquals(List.of("class Alpha {}"), Files.readAllLines(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("restoreAll() annule les trois cas à la fois - créé, modifié, supprimé")
	void restoreAllUndoesCreatedChangedAndDeleted(@TempDir final Path root) throws IOException {
		write(root, "Changed.java", "class Changed {}");
		final Path toDelete = write(root, "Deleted.java", "class Deleted {}");
		final Transaction transaction = open(root);

		write(root, "Changed.java", "class Changed { int i; }");
		Files.delete(toDelete);
		final Path created = write(root, "Created.java", "class Created {}");

		transaction.restoreAll();

		assertEquals(List.of("class Changed {}"), Files.readAllLines(root.resolve("Changed.java"), StandardCharsets.UTF_8));
		assertEquals(List.of("class Deleted {}"), Files.readAllLines(toDelete, StandardCharsets.UTF_8));
		assertFalse(Files.exists(created));
	}

	@Test
	@DisplayName("modifiedFiles() ne liste que ce qui a bougé depuis l'ouverture")
	void modifiedFilesListsOnlyWhatMoved(@TempDir final Path root) throws IOException {
		write(root, "Untouched.java", "class Untouched {}");
		write(root, "Changed.java", "class Changed {}");
		final Transaction transaction = open(root);

		write(root, "Changed.java", "class Changed { int i; }");
		write(root, "Created.java", "class Created {}");

		assertEquals(List.of("Changed.java", "Created.java"), transaction.modifiedFiles());
	}

	@Test
	@DisplayName("un fichier non-.java n'est jamais protégé - la portée est celle de Snapshot")
	void nonJavaFileIsNeverProtected(@TempDir final Path root) throws IOException {
		final Transaction transaction = open(root);

		Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

		assertFalse(transaction.hasBackup("pom.xml"));
		assertEquals(List.of(), transaction.modifiedFiles());
	}

	@Test
	@DisplayName("le constructeur crée le répertoire marqueur, deleteDirectory() l'efface")
	void directoryIsCreatedThenErasable(@TempDir final Path root) throws IOException {
		final Transaction transaction = open(root);

		assertTrue(Files.isDirectory(transaction.directory()));

		transaction.deleteDirectory();

		assertFalse(Files.exists(transaction.directory()));
	}

	private Path write(final Path root, final String name, final String... lines) throws IOException {
		final Path file = root.resolve(name);
		Files.write(file, List.of(lines));
		return file;
	}

}
