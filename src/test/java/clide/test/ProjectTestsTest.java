package clide.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

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

	@Test
	@DisplayName("le préfixe passe devant le projet, qui passe devant clide")
	void prefixComesFirst() {
		final List<String> full = ProjectTests.assembleClasspath(List.of("/before.jar"),
				List.of("/bin", "/junit-old.jar"), List.of("/clide.jar"));

		assertEquals(List.of("/before.jar", "/bin", "/junit-old.jar", "/clide.jar"), full);
	}

	@Test
	@DisplayName("une entrée déjà présente reste à sa première place, sans doublon")
	void noEntryTwice() {
		final List<String> full = ProjectTests.assembleClasspath(List.of("/a.jar"), List.of("/bin", "/a.jar"),
				List.of("/bin", "/clide.jar"));

		assertEquals(List.of("/a.jar", "/bin", "/clide.jar"), full);
	}

	@Test
	@DisplayName("sans préfixe, l'ordre est celui d'avant : projet puis clide")
	void withoutPrefixNothingChanges() {
		final List<String> full = ProjectTests.assembleClasspath(List.of(), List.of("/bin"), List.of("/clide.jar"));

		assertEquals(List.of("/bin", "/clide.jar"), full);
	}

	@Test
	@DisplayName("les variables demandées arrivent dans l'environnement de la JVM fille, celles du daemon restent")
	void environmentIsAppliedOnTopOfTheDaemons() {
		final ProcessBuilder builder = new ProcessBuilder("java");
		final int before = builder.environment().size();

		ProjectTests.applyEnvironment(builder, Map.of("CLIDE_TEST_PROBE", "on"));

		assertEquals("on", builder.environment().get("CLIDE_TEST_PROBE"));
		assertEquals(before + 1, builder.environment().size());
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

	@Test
	@DisplayName("la JVM de test tourne avec -ea, avant la classe principale")
	void testJvmEnablesAssertions() {
		final List<String> command = ProjectTests.command("java", List.of("a.jar", "b"),
				new String[] { "--class", "demo.CalcTest" });

		// Placé après la classe principale, -ea serait un argument de TestRunnerMain
		// et non une option de la JVM : la position compte autant que la présence.
		assertEquals(List.of("java", "-ea", "-cp", "a.jar" + java.io.File.pathSeparator + "b",
				TestRunnerMain.class.getName(), "--class", "demo.CalcTest"), command);
	}

}
