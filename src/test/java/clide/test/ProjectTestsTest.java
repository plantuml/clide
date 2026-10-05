package clide.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.FilesRepository;
import clide.model.TestMeasure;
import clide.model.TestOutcome;

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
		final List<String> command = ProjectTests.command("java", List.of(), List.of("a.jar", "b"),
				new String[] { "--class", "demo.CalcTest" });

		// Placé après la classe principale, -ea serait un argument de TestRunnerMain
		// et non une option de la JVM : la position compte autant que la présence.
		assertEquals(List.of("java", "-ea", "-cp", "a.jar" + java.io.File.pathSeparator + "b",
				TestRunnerMain.class.getName(), "--class", "demo.CalcTest"), command);
	}

	@Test
	@DisplayName("les options de la JVM viennent après -ea et avant -cp, dans l'ordre demandé")
	void jvmOptionsComeBetweenEaAndClasspath() {
		final List<String> command = ProjectTests.command("java", List.of("-Xmx256m", "-XX:+UseSerialGC"),
				List.of("a.jar"), new String[] { "--class", "demo.CalcTest" });

		// Avant -cp : ce sont des options de la JVM. Après -ea : un -da posé par le
		// client l'emporte sur lui, la dernière option de la ligne gagnant.
		assertEquals(List.of("java", "-ea", "-Xmx256m", "-XX:+UseSerialGC", "-cp", "a.jar",
				TestRunnerMain.class.getName(), "--class", "demo.CalcTest"), command);
	}

	@Test
	@DisplayName("une mesure coupe les assertions du projet avant les options de la connexion, qui peuvent les rétablir")
	void measuresRunWithoutAssertions() {
		assertEquals(List.of("-da", "-Xmx1g", "-XX:Foo"),
				ProjectTests.jvmOptions(false, List.of("-Xmx1g"), List.of("-XX:Foo")));
		assertEquals(List.of("-Xmx1g", "-XX:Foo"), ProjectTests.jvmOptions(true, List.of("-Xmx1g"), List.of("-XX:Foo")));

		// -ea demandé par la connexion vient après -da : la dernière option gagne.
		final List<String> command = ProjectTests.command("java", ProjectTests.jvmOptions(false, List.of("-ea"), List.of()),
				List.of("a.jar"), new String[] { "--class", "demo.T" });
		assertEquals(List.of("java", "-ea", "-da", "-ea", "-cp", "a.jar", TestRunnerMain.class.getName(), "--class",
				"demo.T"), command);
	}

	@Test
	@DisplayName("run_tests slowest : les tests qui ont tourné, du plus lent au plus rapide, sans les ignorés")
	void slowestListsTheLongestFirst(@TempDir final Path root) {
		final List<String> records = List.of(measured("a", 5_000_000, 100), measured("b", 90_000_000, 100),
				skip("c"), measured("d", 40_000_000, 100));

		final List<String> names = names(ProjectTests.report(contextOn(root), "run_tests", records, 10,
				ProjectTests.View.SLOWEST, "demo"));

		assertEquals(List.of("demo.T.b", "demo.T.d", "demo.T.a"), names);
	}

	@Test
	@DisplayName("run_tests heaviest : classé par octets alloués, le nom départage les ex-aequo")
	void heaviestListsTheBiggestAllocatorFirst(@TempDir final Path root) {
		final List<String> records = List.of(measured("z", 1, 500), measured("a", 1, 500), measured("m", 1, 9_000),
				measured("n", 1, 10));

		final List<String> names = names(ProjectTests.report(contextOn(root), "run_tests", records, 10,
				ProjectTests.View.HEAVIEST, "demo"));

		assertEquals(List.of("demo.T.m", "demo.T.a", "demo.T.z", "demo.T.n"), names);
	}

	@Test
	@DisplayName("un test sans mesure passe après tous ceux qui en ont, au lieu de gagner avec -1")
	void unmeasuredTestsComeLast(@TempDir final Path root) {
		// pass() est un enregistrement d'un clide qui ne mesurait pas encore : quatre
		// champs, rien après. Trié tel quel, son -1 le placerait en tête d'un tri
		// croissant et en queue d'un tri décroissant - c'est ce second cas qu'on veut.
		final List<String> records = List.of(pass("sans"), measured("avec", 1_000, 1));

		final List<String> names = names(ProjectTests.report(contextOn(root), "run_tests", records, 10,
				ProjectTests.View.SLOWEST, "demo"));

		assertEquals(List.of("demo.T.avec", "demo.T.sans"), names);
	}

	@Test
	@DisplayName("la vue par défaut garde l'ordre d'exécution, ignorés compris, et mesure chaque test")
	void defaultViewKeepsRunOrder(@TempDir final Path root) {
		final List<String> records = List.of(measured("b", 1_000_000, 10), skip("c"), measured("a", 2_000_000, 20));

		final CommandResult result = ProjectTests.report(contextOn(root), "run_tests", records, 10,
				ProjectTests.View.ALL, "demo");

		assertEquals(List.of("demo.T.b", "demo.T.c", "demo.T.a"), names(result));
		final CommandPayload.TestRun run = (CommandPayload.TestRun) result.payload();
		assertEquals("", run.order());
		assertEquals(new TestMeasure(1_000_000, 900_000, 10, 0, 0), run.tests().items().get(0).measure());
		assertEquals(TestMeasure.UNKNOWN, run.tests().items().get(1).measure());
	}

	@Test
	@DisplayName("un enregistrement PASS sans mesure donne UNKNOWN, pas une exception")
	void recordWithoutMeasureIsStillValid(@TempDir final Path root) {
		final CommandResult result = ProjectTests.report(contextOn(root), "run_test", List.of(pass("a")), 10,
				ProjectTests.View.ALL, "demo");

		final CommandPayload.TestRun run = (CommandPayload.TestRun) result.payload();
		assertEquals(TestMeasure.UNKNOWN, run.tests().items().get(0).measure());
	}

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	private static List<String> names(final CommandResult result) {
		assertTrue(result.payload() instanceof CommandPayload.TestRun, result.toString());
		return ((CommandPayload.TestRun) result.payload()).tests().items().stream().map(TestOutcome::name).toList();
	}

	/** Un PASS tel que la JVM fille l'écrit : les quatre champs, puis les cinq mesures (cpu = 90 % du mur). */
	private static String measured(final String name, final long wallNanos, final long allocatedBytes) {
		return String.join("\t", TestRunnerMain.PASS, "demo.T", name, name + "()", Long.toString(wallNanos),
				Long.toString(wallNanos * 9 / 10), Long.toString(allocatedBytes), "0", "0");
	}

}
