package clide.command.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.model.Listing;

/**
 * Tests de RemoveUnusedImportsCommand qui ne demandent aucun jdtls : le
 * <path regex> qui ne compile pas, le <path regex> qui ne matche aucun
 * fichier, le rendu du compte rendu, et - la seule partie de cette commande
 * qui ne dépend pas d'une vraie session - la suppression des lignes elle-même
 * (removeUnusedImportLines).
 *
 * Ce que ces tests ne couvrent pas volontairement : que jdtls signale bien un
 * import inutilisé avec le problem id "268435844", et que le modèle est
 * ensuite resynchronisé - ça ne veut rien dire sans un vrai serveur en face
 * et se vérifie de bout en bout (clide sur clide), pas ici.
 */
class RemoveUnusedImportsCommandTest {

	// ------------------------------------------------------------------
	// Avant que la session ne soit jamais touchée
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un <path regex> qui ne compile pas est refusé, sans rien demander à jdtls")
	void anInvalidRegexIsRefused(@TempDir final Path root) throws IOException {
		final CommandResult result = removeUnusedImports(root, "[");

		assertEquals(ErrorCode.INVALID_REGEX, result.code());
	}

	@Test
	@DisplayName("un <path regex> qui ne matche aucun fichier du projet est NO_FILES_FOUND")
	void aRegexMatchingNoFileIsRefused(@TempDir final Path root) throws IOException {
		Files.writeString(root.resolve("Square.java"), "package demo;\nclass Square {}\n", StandardCharsets.UTF_8);

		final CommandResult result = removeUnusedImports(root, "NoSuchFile\\.java");

		assertEquals(ErrorCode.NO_FILES_FOUND, result.code());
		assertTrue(result.message().contains("NoSuchFile"), result.message());
	}

	@Test
	@DisplayName("un <path regex> qui matche un fichier réel passe la validation - l'échec qui suit vient de la session absente")
	void aMatchingRegexPassesValidation(@TempDir final Path root) throws IOException {
		Files.writeString(root.resolve("Square.java"), "package demo;\nclass Square {}\n", StandardCharsets.UTF_8);

		assertThrowsNoSessionFailure(() -> removeUnusedImports(root, "Square\\.java"));
	}

	private static void assertThrowsNoSessionFailure(final ThrowingSupplier<CommandResult> call) {
		try {
			call.get();
			throw new AssertionError("expected a NullPointerException from the null session, got none");
		} catch (final NullPointerException expected) {
			// the regex matched a real file, so executeCommand went on to ask the
			// (null, in this test) session for its unused imports - proof the path
			// selection itself was accepted
		} catch (final IOException e) {
			throw new AssertionError(e);
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws IOException;
	}

	// ------------------------------------------------------------------
	// removeUnusedImportLines() - la seule logique testable sans jdtls
	// ------------------------------------------------------------------

	@Test
	@DisplayName("supprime les lignes candidates qui ressemblent à un import, dans l'ordre du fichier")
	void removesCandidateImportLinesInFileOrder(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Demo.java");
		Files.writeString(file, """
				package demo;

				import java.util.List;
				import java.util.ArrayList;

				class Demo {
					List<String> field = new ArrayList<>();
				}
				""", StandardCharsets.UTF_8);

		final List<String> removed = RemoveUnusedImportsCommand.removeUnusedImportLines(file, List.of(3, 4));

		assertEquals(List.of("java.util.List", "java.util.ArrayList"), removed);
		assertEquals("""
				package demo;


				class Demo {
					List<String> field = new ArrayList<>();
				}
				""", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("une ligne candidate qui ne ressemble plus à un import est laissée intacte, pas supprimée à l'aveugle")
	void aCandidateLineNotLookingLikeAnImportIsLeftAlone(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Demo.java");
		final String original = "package demo;\n\nclass Demo {\n}\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);

		// line 3 is "class Demo {" - not an import line at all, e.g. because the
		// file changed since jdtls last reported it
		final List<String> removed = RemoveUnusedImportsCommand.removeUnusedImportLines(file, List.of(3));

		assertEquals(List.of(), removed);
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
				"nothing removed means nothing written back either");
	}

	@Test
	@DisplayName("'static ' est conservé dans le nom rapporté d'un import statique")
	void staticIsKeptInTheReportedName(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Demo.java");
		Files.writeString(file, "package demo;\n\nimport static java.lang.Math.PI;\n\nclass Demo {\n}\n",
				StandardCharsets.UTF_8);

		final List<String> removed = RemoveUnusedImportsCommand.removeUnusedImportLines(file, List.of(3));

		assertEquals(List.of("static java.lang.Math.PI"), removed);
	}

	@Test
	@DisplayName("un numéro de ligne candidat hors du fichier est ignoré plutôt que de lever")
	void aCandidateLineOutOfRangeIsIgnored(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Demo.java");
		final String original = "package demo;\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);

		final List<String> removed = RemoveUnusedImportsCommand.removeUnusedImportLines(file, List.of(0, 42));

		assertEquals(List.of(), removed);
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------
	// Rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("le compte rendu liste les fichiers matchés, changés, les imports perdus, et le build")
	void theReportNamesMatchedChangedFilesAndBuild() {
		final CommandPayload payload = new CommandPayload.RemoveUnusedImports(3,
				Listing.of(List.of(new CommandPayload.RemoveUnusedImports.FileChange("src/demo/Demo.java",
						List.of("java.util.List", "java.util.ArrayList"))), 100),
				0);

		final String rendered = new RemoveUnusedImportsCommand().render(CommandResult.ok(payload), PrintMode.AI);

		assertEquals("""
				remove_unused_imports: 3 file(s) matched, 1 file(s) changed
				src/demo/Demo.java: removed java.util.List, java.util.ArrayList
				rebuilt: 0 error(s)""", rendered);
	}

	@Test
	@DisplayName("des fichiers matchés mais tous déjà propres le disent, plutôt que d'annoncer 0 fichier changé")
	void matchedFilesWithNothingToRemoveSaySo() {
		final CommandPayload payload = new CommandPayload.RemoveUnusedImports(2, Listing.of(List.of(), 100), 0);

		assertEquals("remove_unused_imports: 2 file(s) matched, nothing to remove",
				new RemoveUnusedImportsCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static CommandResult removeUnusedImports(final Path root, final String pathRegex) throws IOException {
		final FilesRepository files_ = new FilesRepository(root, new Md5Repository(root));
		final ClideContext context = new ClideContext(files_, null, List.of());
		return new RemoveUnusedImportsCommand().executeCommand(context, pathRegex);
	}

}
