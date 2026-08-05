package clide.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de CodeLocation : la cohérence qu'il impose entre position et
 * lineText, et la forme imprimée qui en résulte une fois cette cohérence
 * acquise.
 */
class CodeLocationTest {

	@Test
	@DisplayName("display() rend la position, puis la ligne, séparées par une espace")
	void displayIsAWholePositionThenTheLine() {
		final CodeLocation location = new CodeLocation(new Position("src/main/java/demo/Calc.java", 7, 13, "add"),
				"public int add(int a, int b) {");

		assertEquals("src/main/java/demo/Calc.java:7:13:add public int add(int a, int b) {", location.display());
	}

	@Test
	@DisplayName("sans texte de ligne, display() se réduit à la position seule - un nom sans lineText n'est pas cohérent")
	void displayWithoutLineTextIsThePositionAlone() {
		final CodeLocation location = new CodeLocation(new Position("src/main/java/demo/Calc.java", 7, 13, ""), "");

		assertEquals("src/main/java/demo/Calc.java:7:13:", location.display());
	}

	@Test
	@DisplayName("null est refusé pour position et pour lineText")
	void nullIsRefusedEverywhere() {
		assertThrows(IllegalArgumentException.class, () -> new CodeLocation(null, "x"));
		assertThrows(IllegalArgumentException.class,
				() -> new CodeLocation(new Position("A.java", 7, 13, "add"), null));
	}

	@Test
	@DisplayName("sans ligne/colonne utilisable (-1), un texte de ligne non vide est refusé")
	void noUsableLineOrColumnRejectsNonEmptyLineText() {
		assertThrows(IllegalArgumentException.class,
				() -> new CodeLocation(new Position("A.java", -1, -1, ""), "public int add("));
	}

	@Test
	@DisplayName("sans ligne/colonne utilisable (-1), un texte de ligne vide reste accepté")
	void noUsableLineOrColumnAcceptsEmptyLineText() {
		final CodeLocation location = new CodeLocation(new Position("A.java", -1, -1, ""), "");

		assertEquals("A.java:-1:-1:", location.display());
	}

	@Test
	@DisplayName("un nom absent du texte de ligne est refusé - la ligne ne peut pas être celle du nom rapporté")
	void nameMissingFromLineTextIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new CodeLocation(new Position("A.java", 7, 13, "add"), "totally unrelated content"));
	}

	@Test
	@DisplayName("un nom présent seulement comme sous-mot d'un identifiant plus long est refusé")
	void nameOnlyAsSubstringOfALongerIdentifierIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new CodeLocation(new Position("A.java", 7, 13, "sum"), "return summary(a, b);"));
	}

	@Test
	@DisplayName("sans nom, aucune cohérence n'est exigée avec lineText")
	void emptyNameSkipsTheLineTextCheck() {
		final CodeLocation location = new CodeLocation(new Position("A.java", 7, 13, ""), "public int add(");

		assertEquals("A.java:7:13: public int add(", location.display());
	}

}
