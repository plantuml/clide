package clide.core;

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

import clide.command.result.ErrorCode;

/**
 * Tests de Position.parse() sur la notation canonique
 * chemin:ligne:colonne:nom : le code d'erreur porté par chaque refus, et le
 * contrôle de cohérence qui exige que le nom commence bien à la colonne
 * annoncée.
 *
 * Ce contrôle est ce qui remplace l'ancienne résolution implicite : sans
 * colonne, "a.calculer(b.calculer())" désignait deux méthodes sans rapport et
 * clide répondait silencieusement sur la première, avec un simple
 * avertissement. Désormais chaque colonne désigne l'une ou l'autre, et une
 * colonne fausse est refusée plutôt qu'arrondie.
 */
class PositionCodesTest {

	private static Path write(final Path dir, final String name, final String... lines) throws IOException {
		final Path file = dir.resolve(name);
		Files.write(file, List.of(lines));
		return file;
	}

	private static ErrorCode codeOf(final Path root, final String token) {
		final PositionException thrown = assertThrows(PositionException.class, () -> Position.parse(token, root));
		return thrown.getCode();
	}

	@Test
	@DisplayName("une notation qui ne ressemble pas à chemin:ligne:colonne:nom est MALFORMED_POSITION")
	void malformedNotation(@TempDir final Path root) {
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:douze:1:bar"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:12:deux:bar"));
	}

	@Test
	@DisplayName("l'ancienne notation sans colonne n'est plus acceptée")
	void threePartNotationIsRejected(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:1:Foo"));
	}

	@Test
	@DisplayName("un fichier absent est FILE_NOT_FOUND, pas une notation malformée")
	void missingFile(@TempDir final Path root) {
		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "Absent.java:1:1:bar"));
	}

	@Test
	@DisplayName("une ligne hors du fichier est LINE_OUT_OF_RANGE et le message dit combien il y en a")
	void lineOutOfRange(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		final PositionException thrown = assertThrows(PositionException.class,
				() -> Position.parse("Foo.java:99:7:Foo", root));

		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, thrown.getCode());
		assertTrue(thrown.getMessage().contains("file has 2 line(s)"));
	}

	@Test
	@DisplayName("un nom absent de la ligne est NAME_NOT_ON_LINE, quelle que soit la colonne")
	void nameNotOnLine(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:1:1:absent"));
	}

	@Test
	@DisplayName("une colonne exacte est acceptée et conservée telle quelle, 1-based")
	void exactColumnIsAccepted(@TempDir final Path root) throws IOException {
		// "\tvoid calculer() {" : la tabulation occupe la colonne 1, "void " les
		// colonnes 2 à 6, donc "calculer" commence en colonne 7.
		write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");

		final Position position = Position.parse("Foo.java:2:7:calculer", root);

		assertEquals(2, position.line());
		assertEquals(7, position.column());
		assertEquals("calculer", position.name());
	}

	@Test
	@DisplayName("le nom présent ailleurs sur la ligne est NAME_NOT_AT_COLUMN, et le hint donne les vraies colonnes")
	void nameElsewhereOnTheLine(@TempDir final Path root) throws IOException {
		// "\t\ta.calculer(b.calculer());" - deux appels sans rapport sur une ligne.
		// Colonnes 1-based comptées à la main : les deux tabulations occupent 1 et 2,
		// "a." 3-4, donc le premier "calculer" commence en 5 ; "(b." occupe 13-15,
		// donc le second commence en 16.
		write(root, "Foo.java", "class Foo {", "\t\ta.calculer(b.calculer());", "}");

		final PositionException thrown = assertThrows(PositionException.class,
				() -> Position.parse("Foo.java:2:9:calculer", root));

		assertEquals(ErrorCode.NAME_NOT_AT_COLUMN, thrown.getCode());
		assertTrue(thrown.getHint().contains("5"));
		assertTrue(thrown.getHint().contains("16"));
	}

	@Test
	@DisplayName("chaque occurrence d'une ligne ambiguë est atteignable par sa propre colonne")
	void eachOccurrenceHasItsOwnColumn(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\t\ta.calculer(b.calculer());", "}");

		assertEquals(5, Position.parse("Foo.java:2:5:calculer", root).column());
		assertEquals(16, Position.parse("Foo.java:2:16:calculer", root).column());
	}

	@Test
	@DisplayName("la correspondance reste sur le mot entier - calculerTout n'est pas calculer")
	void wholeWordOnly(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\t\tcalculerTout();", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:2:3:calculer"));
	}

	@Test
	@DisplayName("toString() rend la notation canonique complète, colonne comprise")
	void toStringIsTheCanonicalNotation(@TempDir final Path root) throws IOException {
		final Path file = write(root, "Foo.java", "class Foo {", "}");

		assertEquals(file + ":1:7:Foo", Position.parse("Foo.java:1:7:Foo", root).toString());
	}

	@Test
	@DisplayName("PositionException reste une IllegalArgumentException pour les appelants d'avant les codes")
	void stillAnIllegalArgumentException(@TempDir final Path root) {
		assertThrows(IllegalArgumentException.class, () -> Position.parse("Absent.java:1:1:bar", root));
	}

}
