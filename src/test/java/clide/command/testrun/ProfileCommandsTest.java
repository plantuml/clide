package clide.command.testrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.CommandStatus;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.FilesRepository;
import clide.model.Listing;
import clide.model.ProfileOverview;
import clide.model.ProfileRow;
import clide.model.ProfileTable;
import clide.model.TestOutcome;
import clide.profile.ProfiledRun;

/**
 * profile_report et set_profile_scope sur un vrai enregistrement, et le rendu
 * d'un profil. Le profil de profile_test lui-même passe par jdtls et n'est pas
 * joué ici : ce qui l'est, c'est tout ce qui s'y branche.
 */
class ProfileCommandsTest {

	@TempDir
	static Path root;

	private static ProfiledRun run;

	@BeforeAll
	static void record() throws Exception {
		run = ProfiledRun.of(root);
	}

	private static ClideContext context() {
		final ClideContext context = new ClideContext(new FilesRepository(root, null), null, List.of());
		context.setLastRecording(run.recording);
		return context;
	}

	private static String report(final ClideContext context, final String view, final String filter) {
		final ProfileReportCommand command = new ProfileReportCommand();
		return command.render(command.executeCommand(context, view, filter), PrintMode.AI);
	}

	@Test
	@DisplayName("sans profil préalable, profile_report le dit et nomme la commande à lancer")
	void noProfileYet(@TempDir final Path empty) {
		final ClideContext context = new ClideContext(new FilesRepository(empty, null), null, List.of());

		final CommandResult result = new ProfileReportCommand().executeCommand(context, "hot", "*");

		assertEquals(ErrorCode.NO_PROFILE, result.code());
		assertTrue(result.hint().contains("profile_test"), result.hint());
	}

	@Test
	@DisplayName("une vue inconnue est refusée avant toute lecture")
	void unknownView() {
		final CommandResult result = new ProfileReportCommand().executeCommand(context(), "chaud", "*");

		assertEquals(ErrorCode.INVALID_ENUM_VALUE, result.code());
	}

	@Test
	@DisplayName("hot s'imprime « valeur part chemin  Classe.méthode », avec le chemin relatif au projet")
	void hotReadsAsLocationAndName() {
		final String text = report(context(), "hot", "*");

		assertTrue(text.startsWith("== overview\nrecording"), text);
		assertTrue(text.contains("== cpu: attributed"), text);
		assertTrue(text.matches("(?s).*\\d+\\s+\\d+\\.\\d%  src/main/java/fixture/Burner\\.java  Burner\\.\\w+.*"), text);
		assertFalse(text.contains(root.toString()), "aucun chemin absolu : " + text);
	}

	@Test
	@DisplayName("lines ajoute le numéro de ligne, prêt pour read_lines")
	void linesCarryTheLine() {
		assertTrue(report(context(), "lines", "*").matches("(?s).*src/main/java/fixture/Burner\\.java:\\d+  Burner.*"));
	}

	@Test
	@DisplayName("callers donne qui appelle, et refuse « * » en le disant")
	void callers() {
		assertTrue(report(context(), "callers", "Burner.spin").contains("Burner.main"));

		final CommandResult refused = new ProfileReportCommand().executeCommand(context(), "callers", "*");
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, refused.code());
		assertTrue(refused.message().contains("callers"), refused.message());
	}

	@Test
	@DisplayName("overview ne donne que les chiffres d'ensemble, sans table")
	void overviewHasNoTable() {
		final String text = report(context(), "overview", "*");

		assertTrue(text.startsWith("== overview"), text);
		assertFalse(text.contains("== cpu"), text);
	}

	@Test
	@DisplayName("un filtre vide vaut « * »")
	void blankFilterMeansEverything() {
		assertEquals(report(context(), "hot", "*"), report(context(), "hot", "  "));
	}

	@Test
	@DisplayName("la table est plafonnée par max_results et le dit, avec la phrase des autres listes")
	void tableIsCapped() throws Exception {
		final ClideContext context = context();
		context.setMaxResults(1);

		final String text = report(context, "lines", "*");

		assertTrue(text.contains("1 row(s) shown out of"), text);
		assertTrue(text.contains("raise the limit with set_max_results"), text);
	}

	@Test
	@DisplayName("set_profile_scope all remet le code de test dans le projet, et rend l'ancienne valeur")
	void scopeCanBeWidened() {
		final ClideContext context = context();
		final SetProfileScopeCommand command = new SetProfileScopeCommand();

		final CommandResult first = command.executeCommand(context, "all");
		final CommandResult second = command.executeCommand(context, "main");

		assertEquals("set_profile_scope: profile_scope main -> all", command.render(first, PrintMode.AI));
		assertEquals("set_profile_scope: profile_scope all -> main", command.render(second, PrintMode.AI));
		assertFalse(context.isProfileIncludingTests());
	}

	@Test
	@DisplayName("set_profile_scope refuse tout autre mot, et reset_test_settings revient à main")
	void scopeValuesAndReset() {
		final ClideContext context = context();

		assertEquals(ErrorCode.INVALID_ENUM_VALUE,
				new SetProfileScopeCommand().executeCommand(context, "tests").code());

		context.setProfileIncludingTests(true);
		context.resetTestSettings();
		assertFalse(context.isProfileIncludingTests());
	}

	@Test
	@DisplayName("profile_test imprime le verdict des tests puis le profil, et dirige vers profile_report quand il coupe")
	void profiledRendering() {
		final CommandPayload.TestRun tests = new CommandPayload.TestRun("demo.CalcTest", 3, 1, 0, 120,
				Listing.of(List.of(new TestOutcome(TestOutcome.Status.FAILED, "demo.CalcTest.add",
						"src/test/java/demo/CalcTest.java:22", List.of("expected: <5> but was: <4>"), "")), 100),
				true);
		final ProfileTable hot = new ProfileTable("hot", "samples", 200, Listing.of(
				List.of(new ProfileRow(60, 30.0, "src/main/java/demo/Calc.java", "Calc.add"),
						new ProfileRow(20, 10.0, "src/main/java/demo/Calc.java", "Calc.sub")),
				1));
		final ProfileTable alloc = new ProfileTable("alloc", "bytes", 3 * 1024 * 1024, Listing.of(
				List.of(new ProfileRow(2 * 1024 * 1024, 66.7, "src/main/java/demo/Calc.java:9", "Calc.add"),
					new ProfileRow(1024 * 1024, 33.3, "src/main/java/demo/Calc.java:12", "Calc.sub")), 1));
		final CommandPayload.Profiled profiled = new CommandPayload.Profiled(tests,
				new CommandPayload.Profile(new ProfileOverview(500, 200, 20, 2, 9, 5, 3 * 1024 * 1024, 4, 0),
						List.of(hot, alloc)));

		final String text = new ProfileTestCommand().render(CommandResult.ok(profiled), PrintMode.AI);

		assertTrue(text.startsWith("profile_test: 4 test(s), 3 passed, 1 failed in 120 ms"), text);
		assertTrue(text.contains("[failed] src/test/java/demo/CalcTest.java:22: demo.CalcTest.add"), text);
		assertTrue(text.contains("== overview\nrecording     500 ms\ncpu samples   200  (20 with no frame"), text);
		assertTrue(text.contains("gc            2 collection(s), 9 ms paused in total, longest 5 ms"), text);
		assertTrue(text.contains("allocation    ~3.0 MB sampled weight"), text);
		assertTrue(text.contains("        60  30.0%  src/main/java/demo/Calc.java  Calc.add"), text);
		assertTrue(text.contains("   2.0 MB  66.7%  src/main/java/demo/Calc.java:9  Calc.add"), text);
		assertTrue(text.contains("1 row(s) shown out of 2 - profile_report alloc * lists more"), text);
		assertFalse(text.contains("set_max_results"), text);
		assertEquals(CommandStatus.OK, CommandResult.ok(profiled).status());
	}

	@Test
	@DisplayName("les pourcentages gardent le point décimal quelle que soit la locale de la JVM")
	void percentagesDoNotDependOnTheLocale() {
		final java.util.Locale saved = java.util.Locale.getDefault();
		java.util.Locale.setDefault(java.util.Locale.FRANCE);
		try {
			final ProfileTable hot = new ProfileTable("hot", "samples", 200,
					Listing.of(List.of(new ProfileRow(60, 30.0, "src/main/java/demo/Calc.java", "Calc.add")), 1));

			final String text = ProfileRendering.summary(new CommandPayload.Profile(
					new ProfileOverview(500, 200, 20, 2, 9, 5, 3 * 1024 * 1024, 4, 0), List.of(hot)));

			assertTrue(text.contains("30.0%"), text);
		} finally {
			java.util.Locale.setDefault(saved);
		}
	}

}
