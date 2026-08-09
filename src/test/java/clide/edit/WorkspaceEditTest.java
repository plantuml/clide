package clide.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de WorkspaceEdit : l'application d'un edit sur l'arbre de fichiers.
 *
 * Tout ici est écrit à la main — aucun jdtls n'est démarré, et c'est le but.
 * L'arithmétique de découpage (offsets, ordre inverse, chevauchements, fins de
 * ligne) est la partie de la commande d'édition qui peut se tromper en
 * silence : elle produit un fichier qui compile encore, mais pas celui que le
 * refactoring voulait. Elle ne vaut donc d'être crue que vérifiée contre des
 * entrées dont on connaît la réponse à la main.
 *
 * Le cas le plus important de ce fichier est
 * {@link #editsApplyBackToFront()} : avec des remplacements de même longueur
 * que le texte remplacé, l'implémentation naïve (avant en arrière) donne la
 * même réponse que la bonne. Il faut des longueurs différentes pour que le
 * bug apparaisse — donc un test qui en met.
 */
class WorkspaceEditTest {

	// ------------------------------------------------------------------
	// Découpage d'un fichier
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un remplacement unique porte exactement sur la plage donnée")
	void singleEditReplacesExactlyItsRange(@TempDir final Path root) throws IOException {
		write(root, "Square.java", "class Square {\n\tint side;\n}\n");

		apply(root, fileEdit("Square.java", new TextEdit(1, 7, 1, 13, "Rectangle")));

		assertEquals("class Rectangle {\n\tint side;\n}\n", read(root, "Square.java"));
	}

	@Test
	@DisplayName("plusieurs remplacements sur une même ligne s'appliquent de l'arrière vers l'avant")
	void editsApplyBackToFront(@TempDir final Path root) throws IOException {
		// Deux remplacements de longueurs differentes sur la meme ligne : appliquer
		// le premier decale le second de 6 caracteres. C'est le cas ou une
		// application avant-en-arriere ecrirait au mauvais endroit.
		write(root, "Pair.java", "int a = one + one;\n");

		apply(root, fileEdit("Pair.java", //
				new TextEdit(1, 9, 1, 12, "premier"), //
				new TextEdit(1, 15, 1, 18, "second")));

		assertEquals("int a = premier + second;\n", read(root, "Pair.java"));
	}

	@Test
	@DisplayName("l'ordre dans lequel les edits arrivent ne change pas le résultat")
	void receivedOrderDoesNotMatter(@TempDir final Path root) throws IOException {
		write(root, "Pair.java", "int a = one + one;\n");

		apply(root, fileEdit("Pair.java", //
				new TextEdit(1, 15, 1, 18, "second"), //
				new TextEdit(1, 9, 1, 12, "premier")));

		assertEquals("int a = premier + second;\n", read(root, "Pair.java"));
	}

	@Test
	@DisplayName("un remplacement peut couvrir plusieurs lignes")
	void anEditCanSpanSeveralLines(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {\n\tvoid a() {\n\t}\n}\n");

		apply(root, fileEdit("Foo.java", new TextEdit(2, 2, 3, 3, "void b() {\n\t}")));

		assertEquals("class Foo {\n\tvoid b() {\n\t}\n}\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("une plage vide est une insertion, un newText vide une suppression")
	void emptyRangeInsertsAndEmptyTextDeletes(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "ab\n");

		apply(root, fileEdit("Foo.java", new TextEdit(1, 2, 1, 2, "XY")));
		assertEquals("aXYb\n", read(root, "Foo.java"));

		apply(root, fileEdit("Foo.java", new TextEdit(1, 2, 1, 4, "")));
		assertEquals("ab\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("deux edits qui se touchent bout à bout ne sont pas un chevauchement")
	void adjacentEditsAreNotAnOverlap(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "abcd\n");

		apply(root, fileEdit("Foo.java", //
				new TextEdit(1, 1, 1, 3, "X"), //
				new TextEdit(1, 3, 1, 5, "YZ")));

		assertEquals("XYZ\n", read(root, "Foo.java"));
	}

	// ------------------------------------------------------------------
	// Ce qui est preserve
	// ------------------------------------------------------------------

	@Test
	@DisplayName("les fins de ligne CRLF du reste du fichier sont préservées")
	void crlfIsPreservedElsewhereInTheFile(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Square {\r\n\tint side;\r\n}\r\n");

		apply(root, fileEdit("Foo.java", new TextEdit(1, 7, 1, 13, "Rectangle")));

		assertEquals("class Rectangle {\r\n\tint side;\r\n}\r\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("les colonnes se comptent bien après un CRLF, pas décalées d'un par ligne")
	void columnsAreCountedCorrectlyAfterCrlf(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "aaaa\r\nbbbb\r\ncccc\r\n");

		apply(root, fileEdit("Foo.java", new TextEdit(3, 1, 3, 5, "DDDD")));

		assertEquals("aaaa\r\nbbbb\r\nDDDD\r\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("un fichier sans saut de ligne final n'en gagne pas un")
	void aFileWithoutATrailingNewlineDoesNotGetOne(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Square {}");

		apply(root, fileEdit("Foo.java", new TextEdit(1, 7, 1, 13, "Rectangle")));

		assertEquals("class Rectangle {}", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("une colonne au-delà de la fin de ligne est ramenée à la fin de ligne")
	void aColumnPastTheEndOfALineIsClamped(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "ab\ncd\n");

		apply(root, fileEdit("Foo.java", new TextEdit(1, 1, 1, 99, "XYZ")));

		assertEquals("XYZ\ncd\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("les colonnes se comptent en UTF-16, comme LSP et comme String")
	void columnsAreCountedInUtf16CodeUnits(@TempDir final Path root) throws IOException {
		// L'emoji est un seul point de code, mais deux unites UTF-16 : "name"
		// commence donc a la colonne 12 pour LSP comme pour String.charAt().
		write(root, "Foo.java", "// 😀 ok\nint name;\n");

		apply(root, fileEdit("Foo.java", new TextEdit(1, 7, 1, 9, "KO")));

		assertEquals("// 😀 KO\nint name;\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("un edit qui reproduit le fichier à l'identique ne le réécrit pas")
	void anEditThatChangesNothingDoesNotRewriteTheFile(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Square {}");

		final AppliedEdit applied = apply(root, fileEdit("Foo.java", new TextEdit(1, 7, 1, 13, "Square")));

		assertTrue(applied.changedFiles().isEmpty());
		assertEquals(0, applied.textEditCount());
	}

	// ------------------------------------------------------------------
	// Refus
	// ------------------------------------------------------------------

	@Test
	@DisplayName("deux edits qui se chevauchent sont refusés, sans rien écrire")
	void overlappingEditsAreRefusedWithoutWriting(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "abcdef\n");

		final WorkspaceEdit edit = fileEdit("Foo.java", //
				new TextEdit(1, 1, 1, 4, "X"), //
				new TextEdit(1, 3, 1, 6, "Y"));

		final EditApplicationException failure = assertThrows(EditApplicationException.class,
				() -> edit.applyTo(root));
		assertTrue(failure.getMessage().contains("overlapping"), failure.getMessage());
		assertEquals("abcdef\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("une ligne au-delà de la fin du fichier est refusée, sans rien écrire")
	void aLinePastTheEndOfTheFileIsRefused(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "ab\n");

		final WorkspaceEdit edit = fileEdit("Foo.java", new TextEdit(9, 1, 9, 2, "X"));

		final EditApplicationException failure = assertThrows(EditApplicationException.class,
				() -> edit.applyTo(root));
		assertTrue(failure.getMessage().contains("line 9"), failure.getMessage());
		assertEquals("ab\n", read(root, "Foo.java"));
	}

	@Test
	@DisplayName("un edit sur un fichier absent est refusé")
	void anEditOnAMissingFileIsRefused(@TempDir final Path root) {
		final WorkspaceEdit edit = fileEdit("Absent.java", new TextEdit(1, 1, 1, 2, "X"));

		assertThrows(EditApplicationException.class, () -> edit.applyTo(root));
	}

	@Test
	@DisplayName("un chemin qui sort du projet est refusé")
	void aPathEscapingTheProjectIsRefused(@TempDir final Path root) {
		final WorkspaceEdit edit = fileEdit("../dehors.java", new TextEdit(1, 1, 1, 2, "X"));

		final EditApplicationException failure = assertThrows(EditApplicationException.class,
				() -> edit.applyTo(root));
		assertTrue(failure.getMessage().contains("outside the project"), failure.getMessage());
	}

	// ------------------------------------------------------------------
	// Operations de ressource
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un renommage de fichier déplace le fichier et compte les deux noms comme modifiés")
	void aFileRenameMovesTheFileAndCountsBothNames(@TempDir final Path root) throws IOException {
		write(root, "src/Square.java", "class Rectangle {}");

		final AppliedEdit applied = new WorkspaceEdit(
				List.of(ResourceOperation.rename("src/Square.java", "src/Rectangle.java"))).applyTo(root);

		assertFalse(Files.exists(root.resolve("src/Square.java")));
		assertEquals("class Rectangle {}", read(root, "src/Rectangle.java"));
		assertEquals(List.of("src/Rectangle.java", "src/Square.java"), applied.changedFiles());
		assertEquals(1, applied.renames().size());
	}

	@Test
	@DisplayName("le contenu est édité avant le renommage quand jdtls les liste dans cet ordre")
	void contentsAreEditedBeforeTheFileIsRenamed(@TempDir final Path root) throws IOException {
		// C'est exactement la forme qu'a le renommage d'une classe publique :
		// editer Square.java, puis le renommer. L'ordre inverse editerait un
		// fichier qui n'existe plus sous ce nom.
		write(root, "Square.java", "public class Square {}");

		final WorkspaceEdit edit = new WorkspaceEdit(List.of( //
				new FileEdit("Square.java", List.of(new TextEdit(1, 14, 1, 20, "Rectangle"))), //
				ResourceOperation.rename("Square.java", "Rectangle.java")));

		final AppliedEdit applied = edit.applyTo(root);

		assertEquals("public class Rectangle {}", read(root, "Rectangle.java"));
		assertFalse(Files.exists(root.resolve("Square.java")));
		assertEquals(1, applied.textEditCount());
	}

	@Test
	@DisplayName("un renommage vers un nom déjà pris est refusé plutôt qu'écrasé")
	void aRenameOntoAnExistingNameIsRefused(@TempDir final Path root) throws IOException {
		write(root, "Square.java", "class Square {}");
		write(root, "Rectangle.java", "class Rectangle {}");

		final WorkspaceEdit edit = new WorkspaceEdit(
				List.of(ResourceOperation.rename("Square.java", "Rectangle.java")));

		assertThrows(EditApplicationException.class, () -> edit.applyTo(root));
		assertEquals("class Rectangle {}", read(root, "Rectangle.java"));
	}

	@Test
	@DisplayName("une création crée le fichier et ses répertoires parents")
	void aCreateMakesTheFileAndItsParents(@TempDir final Path root) throws IOException {
		new WorkspaceEdit(List.of(ResourceOperation.create("src/main/java/demo/Neuf.java"))).applyTo(root);

		assertTrue(Files.isRegularFile(root.resolve("src/main/java/demo/Neuf.java")));
	}

	@Test
	@DisplayName("une suppression de fichier absent est refusée plutôt que passée sous silence")
	void aDeleteOfAMissingFileIsRefused(@TempDir final Path root) {
		final WorkspaceEdit edit = new WorkspaceEdit(List.of(ResourceOperation.delete("Absent.java")));

		assertThrows(EditApplicationException.class, () -> edit.applyTo(root));
	}

	// ------------------------------------------------------------------
	// Compte rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("le compte rendu donne les fichiers touchés triés et le nombre d'edits, pas de fichiers")
	void theReportCountsEditsNotFiles(@TempDir final Path root) throws IOException {
		write(root, "b/B.java", "one one one\n");
		write(root, "a/A.java", "one\n");

		final AppliedEdit applied = new WorkspaceEdit(List.of( //
				new FileEdit("b/B.java", List.of(new TextEdit(1, 1, 1, 4, "two"), //
						new TextEdit(1, 5, 1, 8, "two"), //
						new TextEdit(1, 9, 1, 12, "two"))), //
				new FileEdit("a/A.java", List.of(new TextEdit(1, 1, 1, 4, "two"))))).applyTo(root);

		assertEquals(List.of("a/A.java", "b/B.java"), applied.changedFiles());
		assertEquals(4, applied.textEditCount());
	}

	@Test
	@DisplayName("un edit sans aucun remplacement est vide, et un edit avec un renommage ne l'est pas")
	void emptinessIsAboutHavingSomethingToDo() {
		assertTrue(WorkspaceEdit.empty().isEmpty());
		assertTrue(new WorkspaceEdit(List.of(new FileEdit("Foo.java", List.of()))).isEmpty());
		assertFalse(new WorkspaceEdit(List.of(ResourceOperation.delete("Foo.java"))).isEmpty());
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static WorkspaceEdit fileEdit(final String path, final TextEdit... edits) {
		return new WorkspaceEdit(List.of(new FileEdit(path, List.of(edits))));
	}

	private static AppliedEdit apply(final Path root, final WorkspaceEdit edit) throws IOException {
		return edit.applyTo(root);
	}

	private static void write(final Path root, final String relative, final String content) throws IOException {
		final Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static String read(final Path root, final String relative) throws IOException {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
	}

}
