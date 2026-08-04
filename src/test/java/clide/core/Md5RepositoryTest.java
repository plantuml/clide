package clide.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de Md5Repository.register() - la signature rendue, et le contenu classé
 * sous cette signature dans .clide/tmp/md5.
 *
 * Deux choses sont vérifiées au-delà du simple "le fichier existe" :
 *
 * - le blob est relisible, et rend exactement les octets d'origine. Un gzip
 * écrit à un niveau de compression inhabituel (BEST_SPEED ici, pas le niveau
 * par défaut) reste du gzip standard : c'est ce que ce test prouve, en le
 * relisant avec un GZIPInputStream ordinaire.
 *
 * - classer deux fois le même contenu ne réécrit pas le blob. C'est ce qui
 * rend un scan de projet quasi gratuit une fois le premier passé, et c'est
 * invisible autrement : réécrire donnerait exactement le même résultat, en
 * payant à chaque fois. Le test le constate sur la date de modification du
 * blob, seule trace observable d'une réécriture.
 *
 * none() est traité à part : c'est le dépôt sans racine, qui calcule la
 * signature sans rien classer nulle part - celui qu'utilisent tous les autres
 * tests, et qui ne doit donc jamais toucher au disque.
 */
class Md5RepositoryTest {

	private static final String EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

	@Test
	@DisplayName("register() rend le md5 du contenu, comme md5Of()")
	void registerReturnsTheMd5OfTheContent(@TempDir final Path projectRoot) throws IOException {
		final Path source = write(projectRoot, "Alpha.java", "class Alpha {}");

		final String md5 = new Md5Repository(projectRoot).register(source);

		assertEquals(Md5Repository.md5Of(source), md5);
	}

	@Test
	@DisplayName("un fichier vide est bien d41d8cd98f00b204e9800998ecf8427e")
	void theEmptyFileHasTheWellKnownMd5(@TempDir final Path projectRoot) throws IOException {
		final Path source = write(projectRoot, "Empty.java", "");

		assertEquals(EMPTY_MD5, new Md5Repository(projectRoot).register(source));
	}

	@Test
	@DisplayName("le contenu est classé sous .clide/tmp/md5/<2 premiers>/<md5>.gz")
	void theContentIsFiledUnderItsSignature(@TempDir final Path projectRoot) throws IOException {
		final Path source = write(projectRoot, "Alpha.java", "class Alpha {}");
		final Md5Repository repository = new Md5Repository(projectRoot);

		final String md5 = repository.register(source);

		final Path blob = projectRoot.resolve(".clide/tmp/md5").resolve(md5.substring(0, 2)).resolve(md5 + ".gz");
		assertEquals(blob, repository.blobPath(md5));
		assertTrue(Files.exists(blob));
	}

	@Test
	@DisplayName("le blob est du gzip standard, et rend les octets d'origine")
	void theBlobGivesTheOriginalBytesBack(@TempDir final Path projectRoot) throws IOException {
		final byte[] content = "class Alpha { int i; }\n".getBytes(StandardCharsets.UTF_8);
		final Path source = projectRoot.resolve("Alpha.java");
		Files.write(source, content);
		final Md5Repository repository = new Md5Repository(projectRoot);

		final String md5 = repository.register(source);

		try (InputStream in = new GZIPInputStream(Files.newInputStream(repository.blobPath(md5)))) {
			assertArrayEquals(content, in.readAllBytes());
		}
	}

	@Test
	@DisplayName("classer deux fois le même contenu ne réécrit pas le blob")
	void filingTheSameContentTwiceRewritesNothing(@TempDir final Path projectRoot) throws IOException {
		final Path source = write(projectRoot, "Alpha.java", "class Alpha {}");
		final Md5Repository repository = new Md5Repository(projectRoot);
		final String md5 = repository.register(source);
		final Path blob = repository.blobPath(md5);
		Files.setLastModifiedTime(blob, java.nio.file.attribute.FileTime.fromMillis(1_000_000));

		final Path copy = write(projectRoot, "Beta.java", "class Alpha {}");
		assertEquals(md5, repository.register(copy));

		assertEquals(1_000_000, Files.getLastModifiedTime(blob).toMillis());
	}

	@Test
	@DisplayName("deux contenus différents donnent deux blobs distincts")
	void twoContentsGiveTwoBlobs(@TempDir final Path projectRoot) throws IOException {
		final Md5Repository repository = new Md5Repository(projectRoot);

		final String first = repository.register(write(projectRoot, "Alpha.java", "class Alpha {}"));
		final String second = repository.register(write(projectRoot, "Beta.java", "class Beta {}"));

		assertFalse(first.equals(second));
		assertTrue(Files.exists(repository.blobPath(first)));
		assertTrue(Files.exists(repository.blobPath(second)));
	}

	@Test
	@DisplayName("none() signe sans rien classer - aucun .clide n'apparaît")
	void noneFilesNothing(@TempDir final Path projectRoot) throws IOException {
		final Path source = write(projectRoot, "Alpha.java", "class Alpha {}");

		assertEquals(Md5Repository.md5Of(source), Md5Repository.none().register(source));

		assertFalse(Files.exists(projectRoot.resolve(".clide")));
	}

	@Test
	@DisplayName("aucun .tmp ne reste derrière une fois le blob classé")
	void noTemporaryFileIsLeftBehind(@TempDir final Path projectRoot) throws IOException {
		final Md5Repository repository = new Md5Repository(projectRoot);
		final String md5 = repository.register(write(projectRoot, "Alpha.java", "class Alpha {}"));

		try (java.util.stream.Stream<Path> walk = Files.walk(repository.blobPath(md5).getParent())) {
			assertTrue(walk.filter(Files::isRegularFile).allMatch(p -> p.toString().endsWith(".gz")));
		}
	}

	private Path write(final Path projectRoot, final String name, final String content) throws IOException {
		final Path file = projectRoot.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

}
