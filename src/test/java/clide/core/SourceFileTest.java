package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de SourceFile - ce qu'un instantané retient d'un fichier : son chemin
 * et le md5 de son contenu, rien d'autre.
 *
 * L'essentiel tient dans ce que la date de modification ne fait plus : deux
 * lectures du même fichier donnent le même SourceFile même si son mtime a
 * bougé entre les deux. C'est ce qui fait qu'un fichier réécrit à l'identique
 * ne déclenche rien en amont, et c'est ici que ça se joue - Snapshot ne fait
 * que comparer ce qu'on lui donne.
 */
class SourceFileTest {

	@Test
	@DisplayName("deux lectures du même fichier intact donnent le même SourceFile, mtime bougé ou non")
	void theMtimeDoesNotShow(@TempDir final Path directory) throws IOException {
		final Path file = write(directory, "Alpha.java", "class Alpha {}");
		final SourceFile before = SourceFile.fromPath(file);

		Files.setLastModifiedTime(file, FileTime.fromMillis(9_000_000));
		final SourceFile after = SourceFile.fromPath(file);

		assertEquals(before, after);
		assertEquals(before.hashCode(), after.hashCode());
		assertEquals(before.sourceFileMd5(), after.sourceFileMd5());
	}

	@Test
	@DisplayName("un contenu différent donne un md5 différent, donc un SourceFile différent")
	void adifferentContentShows(@TempDir final Path directory) throws IOException {
		final Path file = write(directory, "Alpha.java", "class Alpha {}");
		final SourceFile before = SourceFile.fromPath(file);

		write(directory, "Alpha.java", "class Alpha { int i; }");

		assertNotEquals(before, SourceFile.fromPath(file));
	}

	@Test
	@DisplayName("deux fichiers de même contenu restent distincts par leur chemin")
	void thePathIsPartOfTheIdentity(@TempDir final Path directory) throws IOException {
		final SourceFile alpha = SourceFile.fromPath(write(directory, "Alpha.java", "class X {}"));
		final SourceFile beta = SourceFile.fromPath(write(directory, "Beta.java", "class X {}"));

		assertEquals(alpha.sourceFileMd5(), beta.sourceFileMd5());
		assertNotEquals(alpha, beta);
	}

	@Test
	@DisplayName("le md5 est celui du contenu, en hexadécimal minuscule")
	void theMd5IsTheWellKnownOne(@TempDir final Path directory) throws IOException {
		final SourceFile file = SourceFile.fromPath(write(directory, "Alpha.java", "abc"));

		assertEquals("900150983cd24fb0d6963f7d28e17f72", file.sourceFileMd5());
	}

	@Test
	@DisplayName("un fichier absent remonte l'erreur plutôt que de rendre un SourceFile vide")
	void anAbsentFileFails(@TempDir final Path directory) {
		assertThrows(NoSuchFileException.class, () -> SourceFile.fromPath(directory.resolve("Absent.java")));
	}

	private Path write(final Path directory, final String name, final String content) throws IOException {
		final Path file = directory.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

}
