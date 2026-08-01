package clide.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.jdtls.JdtlsLauncher;

/**
 * Fait tourner TestRunnerMain pour de vrai, dans un JVM forké, exactement comme
 * clide le fait — et vérifie ce qui en sort.
 *
 * Ce fichier existe parce qu'il manquait. Les autres tests de ce package ne
 * couvrent que de la logique pure : l'échappement du protocole, le découpage
 * des enregistrements, la traduction d'un symbole en sélecteur. Aucun ne
 * lançait JUnit. Résultat : une classe composée uniquement de
 * `@ParameterizedTest` était rapportée « no test found » alors que ses cas
 * s'exécutaient — les résultats, échecs compris, étaient silencieusement jetés.
 * Rien dans la suite ne pouvait le voir.
 *
 * Le fork est délibéré plutôt qu'un appel en direct : `TestRunnerMain.main()`
 * se termine par un `System.exit()`, et surtout le code de sortie fait partie
 * du contrat avec clide au même titre que les lignes écrites. Le tester sans
 * processus reviendrait à tester autre chose que ce qui tourne. Le prix est
 * d'environ une demi-seconde par cas.
 *
 * Les classes cobayes vivent dans le package `fixture`, hors de `clide`, pour
 * que `ant test` (qui sélectionne `--select-package clide`) ne les ramasse pas
 * — l'une d'elles échoue exprès.
 */
class TestRunnerMainExecutionTest {

	private static final String FIXTURES = "fixture.";

	@Test
	@DisplayName("un @Test nu : le cas que le comptage n'a jamais raté")
	void plainTest() {
		final Run run = runClass("PlainPassing");

		assertEquals(TestRunnerMain.EXIT_OK, run.exit, run.toString());
		run.assertSummary(1, 1, 0, 0);
		assertEquals(1, run.countOf(TestRunnerMain.PASS));
	}

	@Test
	@DisplayName("cinq cas paramétrés sont cinq tests, pas zéro")
	void parameterizedOnlyIsCounted() {
		// LE test qui manquait. Un @ParameterizedTest est un CONTENEUR dans le
		// plan ; ses invocations sont enregistrées dynamiquement à l'exécution.
		// Compter le plan au démarrage rendait 0, et clide jetait tout.
		final Run run = runClass("ParameterizedOnly");

		assertEquals(TestRunnerMain.EXIT_OK, run.exit, run.toString());
		run.assertSummary(5, 5, 0, 0);
		assertEquals(5, run.countOf(TestRunnerMain.PASS));
	}

	@Test
	@DisplayName("un cas paramétré en échec est rapporté comme un échec, pas comme une absence de test")
	void parameterizedFailureIsNotSwallowed() {
		// La pire forme : exit valait EXIT_NO_TEST, donc clide répondait « no test
		// found » et jetait l'échec. Une régression réelle invisible.
		final Run run = runClass("ParameterizedFailing");

		assertEquals(TestRunnerMain.EXIT_FAILURES, run.exit, run.toString());
		run.assertSummary(4, 3, 1, 0);

		final List<String> failures = run.recordsOf(TestRunnerMain.FAIL);
		assertEquals(1, failures.size(), run.toString());
	}

	@Test
	@DisplayName("l'échec porte le nom d'affichage du cas, pas seulement celui de la méthode")
	void failureCarriesTheCaseIdentity() {
		// Sans ça, vingt cas de la même méthode donnent vingt lignes identiques
		// et rien ne dit lequel a cassé.
		final List<String> fields = TestRunnerMain
				.parseRecord(runClass("ParameterizedFailing").recordsOf(TestRunnerMain.FAIL).get(0));

		assertEquals("fixture.ParameterizedFailing", fields.get(1));
		assertEquals("everyValueIsPositive", fields.get(2));
		assertTrue(fields.get(3).contains("-3"), "nom d'affichage : " + fields.get(3));
		assertTrue(fields.get(4).contains("valeur negative : -3"), "message : " + fields.get(4));
		assertTrue(fields.get(5).contains("ParameterizedFailing.java:"), "frame : " + fields.get(5));
	}

	@Test
	@DisplayName("@Test, @ParameterizedTest et @RepeatedTest mélangés font bien neuf tests")
	void mixedShapesAreAllCounted() {
		// Le cas silencieux : l'ancien comptage annonçait « 2 test(s), 9 passed »,
		// une ligne de résumé qui se contredit elle-même sans alerter personne.
		final Run run = runClass("Mixed");

		assertEquals(TestRunnerMain.EXIT_OK, run.exit, run.toString());
		run.assertSummary(9, 9, 0, 0);
	}

	@Test
	@DisplayName("une classe @Disabled compte son test comme sauté, pas comme inexistant")
	void disabledClassCountsItsTestsAsSkipped() {
		final Run run = runClass("DisabledClass");

		assertEquals(TestRunnerMain.EXIT_OK, run.exit, run.toString());
		run.assertSummary(1, 0, 0, 1);
		assertEquals(1, run.countOf(TestRunnerMain.SKIP));
	}

	@Test
	@DisplayName("une classe absente du classpath le dit, plutôt que de laisser JUnit échouer sur la découverte")
	void missingClassIsReportedAsSuch() {
		final Run run = runClass("PasCompileeDuTout");

		assertEquals(TestRunnerMain.EXIT_NO_TEST, run.exit, run.toString());
		assertEquals(1, run.countOf(TestRunnerMain.NOCLASS));
		assertTrue(run.recordsOf(TestRunnerMain.NOCLASS).get(0).contains("fixture.PasCompileeDuTout"));
	}

	@Test
	@DisplayName("une méthode seule se sélectionne aussi")
	void singleMethodSelector() {
		final Run run = run("--method", FIXTURES + "Mixed#plainOne");

		assertEquals(TestRunnerMain.EXIT_OK, run.exit, run.toString());
		run.assertSummary(1, 1, 0, 0);
	}

	// ------------------------------------------------------------------

	private static Run runClass(final String simpleName) {
		return run("--class", FIXTURES + simpleName);
	}

	/**
	 * Forke un JVM sur le classpath de ce test — qui porte déjà TestRunnerMain,
	 * la plateforme JUnit et les cobayes compilés.
	 */
	private static Run run(final String selector, final String value) {
		final List<String> command = List.of(JdtlsLauncher.javaExecutable(), "-cp",
				System.getProperty("java.class.path"), TestRunnerMain.class.getName(), selector, value);

		try {
			final Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
			final String stdout = readAll(process.getInputStream());
			final String stderr = readAll(process.getErrorStream());
			if (process.waitFor(120, TimeUnit.SECONDS) == false) {
				process.destroyForcibly();
				throw new IllegalStateException("le JVM cobaye n'a pas rendu la main");
			}
			return new Run(stdout, stderr, process.exitValue());
		} catch (final IOException | InterruptedException e) {
			throw new IllegalStateException("impossible de forker le JVM cobaye", e);
		}
	}

	private static String readAll(final InputStream stream) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		stream.transferTo(buffer);
		return buffer.toString(StandardCharsets.UTF_8);
	}

	/** Ce qu'un JVM cobaye a produit : ses lignes, son stderr, son code de sortie. */
	private static final class Run {

		private final List<String> lines = new ArrayList<>();
		private final String stderr;
		private final int exit;

		private Run(final String stdout, final String stderr, final int exit) {
			for (final String line : stdout.split("\n"))
				if (line.isBlank() == false)
					lines.add(line.strip());

			this.stderr = stderr;
			this.exit = exit;
		}

		private List<String> recordsOf(final String kind) {
			final List<String> found = new ArrayList<>();
			for (final String line : lines)
				if (line.startsWith(kind + "\t"))
					found.add(line);

			return found;
		}

		private int countOf(final String kind) {
			return recordsOf(kind).size();
		}

		private void assertSummary(final int found, final int passed, final int failed, final int skipped) {
			final List<String> summaries = recordsOf(TestRunnerMain.SUMMARY);
			assertEquals(1, summaries.size(), "une seule ligne SUMMARY attendue :\n" + this);

			final List<String> fields = TestRunnerMain.parseRecord(summaries.get(0));
			assertEquals(List.of(Integer.toString(found), Integer.toString(passed), Integer.toString(failed),
					Integer.toString(skipped)), fields.subList(1, 5), "SUMMARY :\n" + this);
		}

		@Override
		public String toString() {
			return "exit=" + exit + "\n" + String.join("\n", lines)
					+ (stderr.isBlank() ? "" : "\n--- stderr ---\n" + stderr);
		}
	}

}
