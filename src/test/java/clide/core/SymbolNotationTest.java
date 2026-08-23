package clide.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.ErrorCode;
import clide.jdtls.LspClient;
import clide.model.Position;

/**
 * SYMBOLS.md's three notations added on top of the canonical
 * &lt;path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt; (see PositionCodesTest for that
 * one, unchanged) - Classe::membre, Classe/Outer.Inner seule, and the
 * NomFichier.java:&lt;line&gt;:&lt;column&gt;:&lt;name&gt; filename shortcut.
 *
 * Only what is reachable without a live jdtls session is exercised here:
 * NomFichier.java is a project-wide filename search (FilesRepository alone,
 * see PositionParser.resolveFilenameAlone()) and every MALFORMED_POSITION
 * refusal is grammar-only (see PositionParser.preValidate()) - both offline
 * by construction, so a null JdtlsSession is passed exactly where production
 * code never would (it always has a live one - see CommandDispatcher) and
 * exercises only the branches that never touch it. Classe::membre and
 * Classe/Outer.Inner's actual *resolution* needs a live jdtls model to mean
 * anything - no test here fakes one; that is what "clide sur clide" (see
 * HISTORY.md) verifies live instead.
 */
class SymbolNotationTest {

	private static Path write(final Path dir, final String name, final String... lines) throws IOException {
		final Path file = dir.resolve(name);
		Files.write(file, List.of(lines));
		return file;
	}

	private static ErrorCode codeOf(final Path root, final String token) {
		final FilesRepository filesRepository = new FilesRepository(root, null);
		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, null, token));
		return thrown.getCode();
	}

	// ------------------------------------------------------------------
	// NomFichier.java:ligne:colonne:nom - le raccourci par nom de fichier
	// ------------------------------------------------------------------

	@Test
	@DisplayName("NomFichier.java seul retrouve le fichier ou qu'il soit dans le projet, quand il est unique")
	void bareFilenameFindsTheOnlyFileOfThatName(@TempDir final Path root)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		Files.createDirectories(root.resolve("src/main/java/pkg"));
		write(root, "src/main/java/pkg/Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final Position position = PositionParser.parse(filesRepository, null, "Foo.java:1:7:Foo");

		assertEquals("src/main/java/pkg/Foo.java", position.path());
		assertEquals(1, position.line());
		assertEquals(7, position.column());
	}

	@Test
	@DisplayName("NomFichier.java absent du projet est SYMBOL_NOT_FOUND")
	void bareFilenameNotFoundIsSymbolNotFound(@TempDir final Path root) {
		assertEquals(ErrorCode.SYMBOL_NOT_FOUND, codeOf(root, "Absent.java:1:1:bar"));
	}

	@Test
	@DisplayName("NomFichier.java present deux fois dans le projet est AMBIGUOUS_SYMBOL, avec les deux chemins en hint")
	void bareFilenamePresentTwiceIsAmbiguous(@TempDir final Path root) throws IOException {
		Files.createDirectories(root.resolve("a"));
		Files.createDirectories(root.resolve("b"));
		write(root, "a/Foo.java", "class Foo {", "}");
		write(root, "b/Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		final PositionException thrown = assertThrows(PositionException.class,
				() -> PositionParser.parse(filesRepository, null, "Foo.java:1:7:Foo"));

		assertEquals(ErrorCode.AMBIGUOUS_SYMBOL, thrown.getCode());
		assertTrue(thrown.getHint().contains("a" + java.io.File.separator + "Foo.java")
				|| thrown.getHint().contains("a/Foo.java"));
		assertTrue(thrown.getHint().contains("b" + java.io.File.separator + "Foo.java")
				|| thrown.getHint().contains("b/Foo.java"));
	}

	@Test
	@DisplayName("une fois le fichier trouve par son nom seul, les memes controles que la notation canonique s'appliquent")
	void bareFilenameStillChecksLineColumnName(@TempDir final Path root) throws IOException {
		Files.createDirectories(root.resolve("pkg"));
		write(root, "pkg/Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		assertEquals(ErrorCode.LINE_OUT_OF_RANGE,
				assertThrows(PositionException.class, () -> PositionParser.parse(filesRepository, null, "Foo.java:99:1:Foo"))
						.getCode());
		assertEquals(ErrorCode.NAME_NOT_ON_LINE,
				assertThrows(PositionException.class, () -> PositionParser.parse(filesRepository, null, "Foo.java:1:1:absent"))
						.getCode());
	}

	@Test
	@DisplayName("un chemin complet (avec separateur) n'est jamais traite comme le raccourci par nom de fichier")
	void pathWithSeparatorIsNeverTreatedAsBareFilename(@TempDir final Path root) throws IOException {
		Files.createDirectories(root.resolve("pkg"));
		write(root, "pkg/Foo.java", "class Foo {", "}");

		// "Absent/Foo.java" contient un separateur : c'est un chemin complet
		// (niveau 4), pas le raccourci (niveau 3) - donc FILE_NOT_FOUND, jamais une
		// recherche projet entiere qui aurait trouve pkg/Foo.java.
		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "Absent/Foo.java:1:7:Foo"));
	}

	// ------------------------------------------------------------------
	// MALFORMED_POSITION : ce qui ne correspond a aucune des quatre grammaires
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un jeton qui ne correspond a aucune des quatre notations reste MALFORMED_POSITION")
	void unmatchedGrammarIsMalformed(@TempDir final Path root) {
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "toto!"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Classe::"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Classe::methode(abc)"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "..Classe"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Classe.."));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Outer..Inner"));
	}

	@Test
	@DisplayName("Classe seule et Classe::membre sont grammaticalement valides - pas MALFORMED_POSITION")
	void wellFormedNewNotationsAreNotMalformed(@TempDir final Path root) {
		final FilesRepository filesRepository = new FilesRepository(root, null);

		// preValidate() ne fait qu'une passe grammaticale pour ces deux niveaux -
		// aucun appel a jdtls, donc rien ne doit lever ici, meme sans session.
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "MaClasse"));
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "Outer.Inner"));
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "MaClasse::champ"));
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "MaClasse::methode()"));
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "MaClasse::methode(2)"));
		assertDoesNotThrow(() -> PositionParser.preValidate(filesRepository, "Outer.Inner::methode()"));
	}

	@Test
	@DisplayName("preValidate() resout completement la notation canonique et le raccourci par nom de fichier, hors ligne")
	void preValidateFullyResolvesTheOfflineLevels(@TempDir final Path root) throws IOException {
		Files.createDirectories(root.resolve("pkg"));
		write(root, "pkg/Foo.java", "class Foo {", "}");
		final FilesRepository filesRepository = new FilesRepository(root, null);

		// Le chemin complet est verifie completement - une ligne hors bornes est
		// deja detectee ici, avant meme que la commande ne s'execute.
		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, assertThrows(PositionException.class,
				() -> PositionParser.preValidate(filesRepository, "pkg/Foo.java:99:1:Foo")).getCode());

		// Le raccourci par nom de fichier aussi.
		assertEquals(ErrorCode.SYMBOL_NOT_FOUND,
				assertThrows(PositionException.class, () -> PositionParser.preValidate(filesRepository, "Absent.java:1:1:bar"))
						.getCode());
	}

	@Test
	@DisplayName("PositionException depuis parse(FilesRepository, JdtlsSession, String) reste une IllegalArgumentException")
	void positionExceptionStillAnIllegalArgumentException(@TempDir final Path root) {
		final FilesRepository filesRepository = new FilesRepository(root, null);

		assertThrows(IllegalArgumentException.class, () -> PositionParser.parse(filesRepository, null, "toto!"));
	}

	/** Compile-time proof of the checked exceptions parse(FilesRepository, JdtlsSession, String) declares. */
	@SuppressWarnings("unused")
	private static void checkedExceptionsAreDeclared() throws IOException, InterruptedException, LspClient.TimeoutException {
		PositionParser.parse(null, null, "");
	}

}
