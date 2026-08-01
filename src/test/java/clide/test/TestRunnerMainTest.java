package clide.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests du protocole de ligne que la JVM forkée parle vers clide.
 *
 * Ce n'est pas de la cosmétique : un message d'échec contient couramment des
 * sauts de ligne (un assertEquals sur des chaînes multilignes) et des
 * tabulations (du code indenté cité dans le message). Sans échappement, un seul
 * de ces caractères décale tous les champs du reste de l'enregistrement, et
 * clide lit un chemin là où il y avait un message.
 */
class TestRunnerMainTest {

	@Test
	@DisplayName("échapper puis désenchapper rend le texte d'origine")
	void roundTrips() {
		for (final String original : List.of("", "simple", "avec\ttabulation", "deux\nlignes",
				"retour\r\nchariot", "antislash \\ seul", "déjà échappé \\t \\n", "tout\t\n\r\\ ensemble",
				"accents éàü et ⟶ unicode")) {
			assertEquals(original, TestRunnerMain.unescape(TestRunnerMain.escape(original)), original);
		}
	}

	@Test
	@DisplayName("un texte échappé ne contient plus aucun séparateur")
	void escapedTextHoldsNoSeparator() {
		final String escaped = TestRunnerMain.escape("expected:\n\t<a>\nbut was:\n\t<b>");

		assertEquals(-1, escaped.indexOf('\t'));
		assertEquals(-1, escaped.indexOf('\n'));
		assertEquals(-1, escaped.indexOf('\r'));
	}

	@Test
	@DisplayName("un message multiligne reste un seul enregistrement de six champs")
	void multilineMessageStaysOneRecord() {
		final String message = "expected:\n  <99>\nbut was:\n  <5>";
		final String record = String.join("\t", TestRunnerMain.FAIL, "demo.CalcTest", "deliberatelyFails",
				TestRunnerMain.escape("deliberatelyFails()"), TestRunnerMain.escape(message),
				TestRunnerMain.escape("at demo.CalcTest.deliberatelyFails(CalcTest.java:22)"),
				TestRunnerMain.escape(""));

		final List<String> fields = TestRunnerMain.parseRecord(record);

		assertEquals(7, fields.size());
		assertEquals(TestRunnerMain.FAIL, fields.get(0));
		assertEquals("demo.CalcTest", fields.get(1));
		assertEquals(message, fields.get(4));
		assertEquals("at demo.CalcTest.deliberatelyFails(CalcTest.java:22)", fields.get(5));
		assertEquals("", fields.get(6));
	}

	@Test
	@DisplayName("un champ vide en fin d'enregistrement n'est pas avalé")
	void trailingEmptyFieldSurvives() {
		// split() sans limite négative supprimerait les champs vides finaux, et
		// l'enregistrement perdrait sa forme fixe.
		assertEquals(List.of("FAIL", "a", "b", "", ""), TestRunnerMain.parseRecord("FAIL\ta\tb\t\t"));
	}

	@Test
	@DisplayName("un antislash littéral ne se confond pas avec une séquence d'échappement")
	void literalBackslashIsNotAnEscape() {
		final String windowsPath = "C:\\github\\clide\\src";

		assertEquals(windowsPath, TestRunnerMain.unescape(TestRunnerMain.escape(windowsPath)));
		assertNotEquals(windowsPath, TestRunnerMain.escape(windowsPath));
	}

	@Test
	@DisplayName("escape(null) rend une chaîne vide plutôt que de lever")
	void nullBecomesEmpty() {
		// Un throwable sans message, un displayName absent : le protocole doit
		// écrire un champ vide, pas planter la JVM forkée.
		assertEquals("", TestRunnerMain.escape(null));
	}

	@Test
	@DisplayName("les codes de sortie sont tous distincts")
	void exitCodesAreDistinct() {
		// clide ne distingue « tout passe », « ça a échoué », « aucun test » et
		// « le lanceur est cassé » que par là.
		final List<Integer> codes = List.of(TestRunnerMain.EXIT_OK, TestRunnerMain.EXIT_FAILURES,
				TestRunnerMain.EXIT_NO_TEST, TestRunnerMain.EXIT_BROKEN);

		assertEquals(codes.size(), codes.stream().distinct().count());
		assertEquals(0, TestRunnerMain.EXIT_OK);
	}

}
