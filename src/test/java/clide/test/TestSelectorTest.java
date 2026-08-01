package clide.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de TestSelector, la traduction de la notation "chemin:ligne:nom" de
 * clide vers ce que TestRunnerMain sait sélectionner.
 *
 * Toute la logique est pure - ni jdtls, ni disque - donc ces tests portent
 * exactement sur les règles de nommage, sans montage.
 */
class TestSelectorTest {

	@Test
	@DisplayName("un symbole nommant la classe lance toute la classe")
	void namingTheClassRunsAllOfIt() {
		assertArrayEquals(new String[] { "--class", "demo.CalcTest" },
				TestSelector.selector("package demo;", "CalcTest.java", "CalcTest"));
	}

	@Test
	@DisplayName("un symbole nommant autre chose lance cette seule méthode")
	void namingAMethodRunsThatMethod() {
		assertArrayEquals(new String[] { "--method", "demo.CalcTest#addWorks" },
				TestSelector.selector("package demo;", "CalcTest.java", "addWorks"));
	}

	@Test
	@DisplayName("un fichier sans package reste dans le package par défaut")
	void defaultPackageIsNotQualified() {
		assertArrayEquals(new String[] { "--class", "CalcTest" },
				TestSelector.selector("class CalcTest {}", "CalcTest.java", "CalcTest"));
		assertEquals("", TestSelector.packageOf("class CalcTest {}"));
	}

	@Test
	@DisplayName("un package profond est repris en entier")
	void deepPackage() {
		assertEquals("net.sourceforge.plantuml.klimt.shape",
				TestSelector.packageOf("package net.sourceforge.plantuml.klimt.shape;\n\nclass X {}\n"));
	}

	@Test
	@DisplayName("un bloc de commentaire en colonne 0 ne se fait pas passer pour le package")
	void commentedOutPackageDoesNotWin() {
		// LE cas qui compte, et le seul que l'ancrage ^\\s* ne suffit pas à
		// écarter : rien ne précède "package" sur la ligne, l'expression
		// régulière seule mordrait dessus. Un mauvais nom de classe donnerait un
		// « 0 test trouvé » - un échec qui ressemble à une suite verte, la
		// famille de bug que ce projet chasse.
		final String source = """
				/*
				package com.example.faux;
				*/
				package demo.real;

				class RealTest {}
				""";

		assertEquals("demo.real", TestSelector.packageOf(source));
	}

	@Test
	@DisplayName("un en-tête de licence à étoiles ne gêne pas non plus")
	void licenceHeaderDoesNotWin() {
		// Chaque fichier de PlantUML s'ouvre sur une quarantaine de lignes de
		// licence. Celles-ci commencent par " * ", que l'ancrage écarte déjà -
		// ce test verrouille le cas réel, pas le mécanisme.
		final String source = """
				/*
				 * PlantUML : a free UML diagram generator
				 * You may obtain a copy of the License at
				 *     package com.example.faux;
				 */
				package net.sourceforge.plantuml.real;

				class RealTest {}
				""";

		assertEquals("net.sourceforge.plantuml.real", TestSelector.packageOf(source));
	}

	@Test
	@DisplayName("un commentaire de fin de ligne ne compte pas non plus")
	void lineCommentDoesNotWin() {
		final String source = """
				//
				//package com.example.faux;
				package demo.real;

				class X {}
				""";

		assertEquals("demo.real", TestSelector.packageOf(source));
	}

	@Test
	@DisplayName("un commentaire non refermé ne fait pas boucler ni exploser")
	void unterminatedCommentIsSurvivable() {
		assertEquals("", TestSelector.packageOf("/* package a.b; \nclass X {}"));
		assertEquals("", TestSelector.packageOf("/"));
		assertEquals("", TestSelector.packageOf(""));
	}

	@Test
	@DisplayName("le nom de type vient du nom de fichier, extension retirée")
	void typeNameComesFromTheFileName() {
		assertEquals("CalcTest", TestSelector.typeName("CalcTest.java"));
		assertEquals("Calc", TestSelector.typeName("Calc.java"));
		assertEquals("NoExtension", TestSelector.typeName("NoExtension"));
	}

	@Test
	@DisplayName("un package déclaré avec des espaces bizarres est quand même lu")
	void whitespaceTolerantDeclaration() {
		assertEquals("a.b", TestSelector.packageOf("\n\n   package   a.b   ;\nclass X {}"));
	}

}
