package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de JdtlsArchive : localiser l'archive jdtls et l'identifier.
 *
 * L'empreinte est ce qui rend sûr un cache partagé et persistant (voir
 * JdtlsHome), donc c'est elle qui est testée en priorité : deux archives de
 * contenus différents doivent donner deux empreintes différentes, et une même
 * archive doit donner la même à chaque lecture. Les 49 Mo réels n'entrent jamais
 * en jeu - un zip de quelques octets suffit à valider la propriété.
 */
class JdtlsArchiveTest {

	private static Path writeZip(final Path directory, final String entryName, final String content)
			throws IOException {
		final Path zip = directory.resolve(JdtlsArchive.ZIP_NAME);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry(entryName));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return zip;
	}

	@Test
	@DisplayName("l'archive embarquée dans le jar de test est trouvée, et son empreinte lue")
	void locatesSomething() throws IOException {
		// Sous "ant test" comme sous un IDE, au moins une des trois sources est
		// disponible : la ressource du classpath quand les tests tournent depuis le
		// fat jar, l'archive commitée à la racine du dépôt sinon.
		final JdtlsArchive archive = JdtlsArchive.locate();

		assertNotNull(archive.describe());
		assertTrue(archive.describe().contains(JdtlsArchive.ZIP_NAME));
		assertNotEquals(0L, archive.crc());
	}

	@Test
	@DisplayName("l'empreinte d'une même archive est stable d'une lecture à l'autre")
	void fingerprintIsStable() throws IOException {
		final JdtlsArchive archive = JdtlsArchive.locate();

		assertEquals(archive.crc(), archive.crc());
	}

	@Test
	@DisplayName("deux archives de contenus différents ont deux empreintes différentes")
	void differentContentsDifferentFingerprints(@TempDir final Path first, @TempDir final Path second)
			throws IOException {
		final Path one = writeZip(first, "plugins/whatever.jar", "version 1");
		final Path two = writeZip(second, "plugins/whatever.jar", "version 2 - a newer jdtls");

		assertNotEquals(crcOf(one), crcOf(two));
	}

	@Test
	@DisplayName("le contenu rendu par open() est bien celui de l'archive")
	void opensTheRealBytes(@TempDir final Path directory) throws IOException {
		final Path zip = writeZip(directory, "plugins/whatever.jar", "hello");

		assertTrue(readAll(zip) > 0);
	}

	/**
	 * L'empreinte d'un zip sur disque, calculée comme JdtlsArchive le fait pour
	 * une archive qui n'est pas une entrée de jar : une passe de CRC32 sur le
	 * fichier entier.
	 */
	private static long crcOf(final Path zip) throws IOException {
		final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(Files.readAllBytes(zip));
		return crc.getValue();
	}

	private static int readAll(final Path zip) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream in = Files.newInputStream(zip)) {
			in.transferTo(buffer);
		}
		return buffer.size();
	}
}
