package clide.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de la seule chose que CodeLocation décide : sa forme imprimée.
 *
 * L'enjeu tient en une propriété - ce que clide imprime doit être acceptable
 * tel quel par clide. Le premier jeton d'un display() est donc un
 * &lt;position&gt; complet, sans rien à ajouter ni à recompter.
 */
class CodeLocationTest {

	@Test
	@DisplayName("display() rend la notation canonique complète, puis la ligne, séparées par une espace")
	void displayIsAWholePositionThenTheLine() {
		final CodeLocation location = new CodeLocation("src/main/java/demo/Calc.java", 7, 13, "add",
				"public int add(int a, int b) {");

		assertEquals("src/main/java/demo/Calc.java:7:13:add public int add(int a, int b) {", location.display());
		assertEquals("src/main/java/demo/Calc.java:7:13:add", location.position());
	}

	@Test
	@DisplayName("le premier jeton de display() est exactement position() - c'est ce qui rend un résultat recopiable")
	void theFirstTokenOfDisplayIsThePosition() {
		final CodeLocation location = new CodeLocation("src/main/java/demo/Calc.java", 12, 25, "add",
				"return add(add(a, 1), add(a, 2));");

		assertEquals(location.position(), location.display().split(" ", 2)[0]);
	}

	@Test
	@DisplayName("sans texte de ligne, display() se réduit à la position seule")
	void displayWithoutLineTextIsThePositionAlone() {
		final CodeLocation location = new CodeLocation("src/main/java/demo/Calc.java", 7, 13, "add", "");

		assertEquals("src/main/java/demo/Calc.java:7:13:add", location.display());
	}

	@Test
	@DisplayName("sans nom, position() s'arrête à la colonne - une réponse incomplète, pas une position inventée")
	void positionWithoutNameStopsAtTheColumn() {
		final CodeLocation location = new CodeLocation("src/main/java/demo/Calc.java", 7, 13, "", "public int add(");

		assertEquals("src/main/java/demo/Calc.java:7:13", location.position());
		assertEquals("src/main/java/demo/Calc.java:7:13 public int add(", location.display());
	}

	@Test
	@DisplayName("null est refusé partout - \"\" est la façon de dire 'inconnu'")
	void nullIsRefusedEverywhere() {
		assertThrows(IllegalArgumentException.class, () -> new CodeLocation(null, 7, 13, "add", "x"));
		assertThrows(IllegalArgumentException.class, () -> new CodeLocation("A.java", 7, 13, null, "x"));
		assertThrows(IllegalArgumentException.class, () -> new CodeLocation("A.java", 7, 13, "add", null));
	}

}
