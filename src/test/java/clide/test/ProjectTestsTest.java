package clide.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests du comptage côté clide.
 *
 * Un seul invariant compte ici, et c'est celui dont l'absence a coûté vingt
 * résultats : **les totaux se déduisent des enregistrements, de rien d'autre**.
 * La ligne SUMMARY porte ses propres compteurs, et ils sont ignorés. Quand la
 * même information voyage deux fois sous deux formes, c'est toujours la
 * mauvaise copie qui finit par gagner.
 */
class ProjectTestsTest {

	@Test
	@DisplayName("les totaux comptent les enregistrements")
	void countsRecords() {
		final List<String> records = List.of(pass("a"), pass("b"), fail("c"), skip("d"), pass("e"));

		assertArrayEquals(new int[] { 3, 1, 1 }, ProjectTests.tally(records));
	}

	@Test
	@DisplayName("une ligne SUMMARY qui ment n'a aucun effet sur les totaux")
	void lyingSummaryIsIgnored() {
		// Exactement ce que produisait la JVM fille sur une classe composée
		// uniquement de @ParameterizedTest : cinq PASS bien réels, et un SUMMARY
		// annonçant zéro test trouvé. clide croyait le compteur et jetait les
		// cinq résultats.
		final List<String> records = List.of(pass("a"), pass("b"), pass("c"), pass("d"), pass("e"),
				String.join("\t", TestRunnerMain.SUMMARY, "0", "5", "0", "0", "440"));

		assertArrayEquals(new int[] { 5, 0, 0 }, ProjectTests.tally(records));
	}

	@Test
	@DisplayName("aucun enregistrement, aucun total - même avec un SUMMARY optimiste")
	void noRecordsMeansZero() {
		assertArrayEquals(new int[] { 0, 0, 0 }, ProjectTests.tally(List.of()));
		assertArrayEquals(new int[] { 0, 0, 0 },
				ProjectTests.tally(List.of(String.join("\t", TestRunnerMain.SUMMARY, "9", "9", "0", "0", "1"))));
	}

	@Test
	@DisplayName("un enregistrement inconnu est ignoré sans faire tomber le comptage")
	void unknownRecordIsIgnored() {
		// Le protocole peut gagner un type d'enregistrement ; une version de clide
		// qui ne le connaît pas doit continuer à compter ce qu'elle comprend.
		final List<String> records = List.of(pass("a"), "PLUSTARD\tquelque\tchose", fail("b"));

		assertArrayEquals(new int[] { 1, 1, 0 }, ProjectTests.tally(records));
	}

	private static String pass(final String name) {
		return String.join("\t", TestRunnerMain.PASS, "demo.T", name, name + "()");
	}

	private static String fail(final String name) {
		return String.join("\t", TestRunnerMain.FAIL, "demo.T", name, name + "()", "boum", "", "");
	}

	private static String skip(final String name) {
		return String.join("\t", TestRunnerMain.SKIP, "demo.T", name, name + "()", "desactive");
	}

}
