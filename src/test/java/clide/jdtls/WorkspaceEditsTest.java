package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.core.Monomorphic;
import clide.edit.EditApplicationException;
import clide.edit.EditOperation;
import clide.edit.FileEdit;
import clide.edit.ResourceOperation;
import clide.edit.ResourceOperationKind;
import clide.edit.TextEdit;
import clide.edit.WorkspaceEdit;
import clide.json.Json;

/**
 * Tests de WorkspaceEdits : la lecture du WorkspaceEdit que jdtls renvoie.
 *
 * Les entrées sont du JSON écrit à la main, dans les formes que le protocole
 * autorise — pas des captures d'un jdtls réel. Ce qui se vérifie ici n'est
 * donc pas « jdtls répond bien ceci » (ça, seul un test de bout en bout le
 * dit) mais « clide lit correctement ce que le protocole permet d'écrire »,
 * y compris les formes que le jdtls du moment n'émet peut-être jamais.
 *
 * Deux propriétés portent l'essentiel :
 *
 * - la conversion 0-based → 1-based, faite ici et nulle part ailleurs
 *   (JdtlsResponses.oneBased) ;
 * - le refus systématique de ce qui n'est pas compris. Un parseur d'edit qui
 *   saute l'opération qu'il n'a pas reconnue produit un refactoring à moitié
 *   appliqué — et un refactoring à moitié appliqué compile assez souvent pour
 *   qu'on le croie.
 */
class WorkspaceEditsTest {

	// ------------------------------------------------------------------
	// Forme "documentChanges"
	// ------------------------------------------------------------------

	@Test
	@DisplayName("les coordonnées LSP 0-based deviennent des coordonnées 1-based")
	void lspZeroBasedCoordinatesBecomeOneBased(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"documentChanges": [
				  {"textDocument": {"uri": "%s", "version": 3},
				   "edits": [{"range": {"start": {"line": 0, "character": 6},
				                        "end":   {"line": 0, "character": 12}},
				              "newText": "Rectangle"}]}
				]}""".formatted(uri(root, "Square.java")));

		final FileEdit fileEdit = (FileEdit) edit.operations().get(0);
		assertEquals("Square.java", fileEdit.path());
		assertEquals(new TextEdit(1, 7, 1, 13, "Rectangle"), fileEdit.edits().get(0));
	}

	@Test
	@DisplayName("un renommage de fichier est lu, et garde sa place dans l'ordre reçu")
	void aFileRenameIsReadAndKeepsItsPlaceInTheOrder(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"documentChanges": [
				  {"textDocument": {"uri": "%s"},
				   "edits": [{"range": {"start": {"line": 0, "character": 13},
				                        "end":   {"line": 0, "character": 19}},
				              "newText": "Rectangle"}]},
				  {"kind": "rename", "oldUri": "%s", "newUri": "%s"}
				]}""".formatted(uri(root, "Square.java"), uri(root, "Square.java"), uri(root, "Rectangle.java")));

		final List<EditOperation> operations = edit.operations();
		assertEquals(2, operations.size());
		assertTrue(operations.get(0) instanceof FileEdit);
		assertEquals(ResourceOperation.rename("Square.java", "Rectangle.java"), operations.get(1));
	}

	@Test
	@DisplayName("les créations et suppressions de fichier sont lues aussi")
	void createAndDeleteAreReadToo(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"documentChanges": [
				  {"kind": "create", "uri": "%s"},
				  {"kind": "delete", "uri": "%s"}
				]}""".formatted(uri(root, "Neuf.java"), uri(root, "Vieux.java")));

		assertEquals(ResourceOperationKind.CREATE, ((ResourceOperation) edit.operations().get(0)).kind());
		assertEquals(ResourceOperationKind.DELETE, ((ResourceOperation) edit.operations().get(1)).kind());
	}

	@Test
	@DisplayName("les sous-répertoires apparaissent en chemin relatif au projet, avec des /")
	void pathsAreProjectRelativeWithForwardSlashes(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"documentChanges": [{"kind": "delete", "uri": "%s"}]}"""
				.formatted(uri(root, "src/main/java/demo/Square.java")));

		assertEquals("src/main/java/demo/Square.java", edit.operations().get(0).path());
	}

	@Test
	@DisplayName("une URI dont le nom contient un espace est décodée, pas laissée en %20")
	void aPercentEncodedUriIsDecoded(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"documentChanges": [{"kind": "delete", "uri": "%s"}]}"""
				.formatted(uri(root, "mon dossier/Square.java")));

		assertEquals("mon dossier/Square.java", edit.operations().get(0).path());
	}

	// ------------------------------------------------------------------
	// Forme "changes" (heritee)
	// ------------------------------------------------------------------

	@Test
	@DisplayName("la forme héritée changes est lue, triée par chemin pour être déterministe")
	void theLegacyChangesFormIsReadSortedByPath(@TempDir final Path root) throws Exception {
		final WorkspaceEdit edit = parse(root, """
				{"changes": {
				  "%s": [{"range": {"start": {"line": 0, "character": 0},
				                    "end":   {"line": 0, "character": 1}}, "newText": "z"}],
				  "%s": [{"range": {"start": {"line": 0, "character": 0},
				                    "end":   {"line": 0, "character": 1}}, "newText": "a"}]
				}}""".formatted(uri(root, "b/B.java"), uri(root, "a/A.java")));

		assertEquals(List.of("a/A.java", "b/B.java"),
				edit.operations().stream().map(EditOperation::path).toList());
	}

	@Test
	@DisplayName("une réponse null, vide ou sans edit donne un WorkspaceEdit vide, pas une erreur")
	void anAbsentOrEmptyResultIsAnEmptyEdit(@TempDir final Path root) throws Exception {
		assertTrue(WorkspaceEdits.parse(null, root).isEmpty());
		assertTrue(WorkspaceEdits.parse(Monomorphic.createNull(), root).isEmpty());
		assertTrue(parse(root, "{}").isEmpty());
		assertTrue(parse(root, "{\"documentChanges\": []}").isEmpty());
	}

	// ------------------------------------------------------------------
	// Refus
	// ------------------------------------------------------------------

	@Test
	@DisplayName("une opération de ressource inconnue est refusée, jamais sautée")
	void anUnknownResourceOperationIsRefused(@TempDir final Path root) {
		final EditApplicationException failure = assertThrows(EditApplicationException.class, () -> parse(root, """
				{"documentChanges": [{"kind": "teleport", "uri": "%s"}]}""".formatted(uri(root, "Foo.java"))));

		assertTrue(failure.getMessage().contains("teleport"), failure.getMessage());
	}

	@Test
	@DisplayName("un edit snippet est refusé plutôt qu'écrit avec ses placeholders")
	void aSnippetEditIsRefused(@TempDir final Path root) {
		final EditApplicationException failure = assertThrows(EditApplicationException.class, () -> parse(root, """
				{"documentChanges": [
				  {"textDocument": {"uri": "%s"},
				   "edits": [{"range": {"start": {"line": 0, "character": 0},
				                        "end":   {"line": 0, "character": 0}},
				              "newText": "${1:nom}", "insertTextFormat": 2}]}
				]}""".formatted(uri(root, "Foo.java"))));

		assertTrue(failure.getMessage().contains("snippet"), failure.getMessage());
	}

	@Test
	@DisplayName("un edit sans range utilisable est refusé")
	void anEditWithoutAUsableRangeIsRefused(@TempDir final Path root) {
		assertThrows(EditApplicationException.class, () -> parse(root, """
				{"documentChanges": [
				  {"textDocument": {"uri": "%s"}, "edits": [{"newText": "X"}]}
				]}""".formatted(uri(root, "Foo.java"))));
	}

	@Test
	@DisplayName("un edit sans newText est refusé - ce n'est pas une suppression implicite")
	void anEditWithoutNewTextIsRefused(@TempDir final Path root) {
		assertThrows(EditApplicationException.class, () -> parse(root, """
				{"documentChanges": [
				  {"textDocument": {"uri": "%s"},
				   "edits": [{"range": {"start": {"line": 0, "character": 0},
				                        "end":   {"line": 0, "character": 1}}}]}
				]}""".formatted(uri(root, "Foo.java"))));
	}

	@Test
	@DisplayName("une URI hors du projet est refusée dès la lecture")
	void aUriOutsideTheProjectIsRefusedAtParseTime(@TempDir final Path root) {
		final EditApplicationException failure = assertThrows(EditApplicationException.class,
				() -> parse(root, """
						{"documentChanges": [{"kind": "delete", "uri": "%s"}]}"""
						.formatted(root.getParent().resolve("dehors.java").toUri())));

		assertTrue(failure.getMessage().contains("outside the project"), failure.getMessage());
	}

	@Test
	@DisplayName("un renommage sans newUri est refusé")
	void aRenameWithoutANewUriIsRefused(@TempDir final Path root) {
		assertThrows(EditApplicationException.class, () -> parse(root, """
				{"documentChanges": [{"kind": "rename", "oldUri": "%s"}]}""".formatted(uri(root, "Foo.java"))));
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static WorkspaceEdit parse(final Path root, final String json) throws EditApplicationException {
		return WorkspaceEdits.parse(Json.parse(json), root);
	}

	private static String uri(final Path root, final String relative) {
		return root.resolve(relative).toUri().toString();
	}

}
