package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.result.ErrorCode;

/**
 * Tests de Position.parse() sur ses deux ajouts : le code d'erreur porté par
 * chaque refus, et la détection d'un nom présent plusieurs fois sur sa ligne.
 *
 * Le second point couvre un comportement qui existait déjà mais ne se voyait
 * pas : "a.foo(b.foo())" désigne deux méthodes sans rapport, et clide résolvait
 * silencieusement la première. Il la résout toujours - c'est ce qui permet de
 * recopier un résultat d'une commande dans la suivante - mais il le dit
 * désormais.
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
	@DisplayName("une notation qui ne ressemble pas à chemin:ligne:nom est MALFORMED_POSITION")
	void malformedNotation(@TempDir final Path root) {
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java"));
		assertEquals(ErrorCode.MALFORMED_POSITION, codeOf(root, "Foo.java:douze:bar"));
	}

	@Test
	@DisplayName("un fichier absent est FILE_NOT_FOUND, pas une notation malformée")
	void missingFile(@TempDir final Path root) {
		assertEquals(ErrorCode.FILE_NOT_FOUND, codeOf(root, "Absent.java:1:bar"));
	}

	@Test
	@DisplayName("une ligne hors du fichier est LINE_OUT_OF_RANGE et le message dit combien il y en a")
	void lineOutOfRange(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		final PositionException thrown = assertThrows(PositionException.class,
				() -> Position.parse("Foo.java:99:Foo", root));

		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, thrown.getCode());
		assertTrue(thrown.getMessage().contains("file has 2 line(s)"));
	}

	@Test
	@DisplayName("un nom absent de la ligne est NAME_NOT_ON_LINE")
	void nameNotOnLine(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:1:absent"));
	}

	@Test
	@DisplayName("un nom présent une seule fois n'est pas ambigu")
	void unambiguousName(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\tvoid calculer() {", "\t}", "}");

		final Position position = Position.parse("Foo.java:2:calculer", root);

		assertFalse(position.isAmbiguousOnLine());
		assertEquals(List.of(6), position.columnsOnLine());
		assertEquals(6, position.column());
	}

	@Test
	@DisplayName("un nom présent deux fois est signalé ambigu, et c'est la première occurrence qui est résolue")
	void ambiguousNameResolvesToTheFirst(@TempDir final Path root) throws IOException {
		// "\t\ta.calculer(b.calculer());" - deux appels sans rapport sur une ligne.
		// Colonnes attendues, comptées à la main sur la chaîne : les deux tabulations
		// occupent 0 et 1, "a." 2-3, donc le premier "calculer" commence en 4 ;
		// "(b." occupe 12-14, donc le second commence en 15.
		write(root, "Foo.java", "class Foo {", "\t\ta.calculer(b.calculer());", "}");

		final Position position = Position.parse("Foo.java:2:calculer", root);

		assertTrue(position.isAmbiguousOnLine());
		assertEquals(List.of(4, 15), position.columnsOnLine());
		assertEquals(4, position.column());
	}

	@Test
	@DisplayName("la correspondance reste sur le mot entier - calculerTout n'est pas calculer")
	void wholeWordOnly(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {", "\t\tcalculerTout();", "}");

		assertEquals(ErrorCode.NAME_NOT_ON_LINE, codeOf(root, "Foo.java:2:calculer"));
	}

	@Test
	@DisplayName("PositionException reste une IllegalArgumentException pour les appelants d'avant les codes")
	void stillAnIllegalArgumentException(@TempDir final Path root) {
		assertThrows(IllegalArgumentException.class, () -> Position.parse("Absent.java:1:bar", root));
	}

}
