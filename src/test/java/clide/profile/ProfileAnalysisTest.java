package clide.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.model.ProfileOverview;
import clide.model.ProfileRow;
import clide.model.ProfileTable;

/**
 * Lit un vrai enregistrement JFR, fait par un vrai JVM forké avec les options de
 * Recording, d'un programme dont on sait ce qu'il fait : tourner dans
 * Burner.spin et Burner.hash, appelés par Burner.main.
 *
 * Un seul enregistrement pour toute la classe : il coûte plus d'une seconde.
 */
class ProfileAnalysisTest {

	@TempDir
	static Path root;

	private static ProfiledRun run;

	@BeforeAll
	static void record() throws Exception {
		run = ProfiledRun.of(root);
	}

	@Test
	@DisplayName("le JVM forké a bien écrit son enregistrement, au nom prévu")
	void recordingIsWritten() {
		assertEquals(1, run.recording.files().size());
		assertEquals("profile-0.jfr", run.recording.files().get(0).getFileName().toString());
	}

	@Test
	@DisplayName("l'enregistrement donne des échantillons CPU, dont la plupart dans le code du projet")
	void samplesAreDenseAndMostlyInScope() throws IOException {
		final ProfileOverview overview = run.recording.overview(run.scope());

		// Le nombre exact dépend de la machine : sur une VM à deux cœurs on obtient
		// quelques dizaines d'échantillons par seconde, bien moins que le millier que
		// la période de 1 ms promet. Le défaut de settings=profile en donne deux ou
		// trois sur la même durée - c'est cette différence-là qu'on vérifie.
		assertTrue(overview.cpuSamples() >= 15, "échantillons : " + overview.cpuSamples());
		assertTrue(overview.samplesOutOfScope() < overview.cpuSamples() / 2, overview.toString());
		assertTrue(overview.recordingMillis() > 1000, overview.toString());
	}

	@Test
	@DisplayName("hot rend la méthode du projet qui consomme, avec son fichier relatif et la part de l'ensemble")
	void hotNamesTheBurner() throws IOException {
		final ProfileTable hot = run.recording.table(run.scope(), ProfileViews.HOT, ProfileViews.NO_FILTER, 10);
		final ProfileRow top = hot.rows().items().get(0);

		assertEquals("src/main/java/fixture/Burner.java", top.location());
		assertTrue(top.name().startsWith("Burner."), top.name());
		assertTrue(top.percent() > 20, "part : " + top.percent());
		assertEquals("samples", hot.unit());
	}

	@Test
	@DisplayName("lines donne le fichier et la ligne, prêts pour read_lines")
	void linesCarryTheLineNumber() throws IOException {
		final ProfileTable lines = run.recording.table(run.scope(), ProfileViews.LINES, ProfileViews.NO_FILTER, 5);

		assertTrue(lines.rows().items().get(0).location().matches("src/main/java/fixture/Burner\\.java:\\d+"),
				lines.rows().items().get(0).location());
	}

	@Test
	@DisplayName("inclusive compte main en dessous de 100 % mais au-dessus de spin : il est sur toute la pile")
	void inclusiveSeesTheWholeStack() throws IOException {
		final ProfileTable inclusive = run.recording.table(run.scope(), ProfileViews.INCLUSIVE, "Burner.main", 5);

		assertEquals(1, inclusive.rows().items().size());
		assertTrue(inclusive.rows().items().get(0).percent() > 50, inclusive.rows().items().get(0).toString());
		assertTrue(inclusive.rows().items().get(0).percent() <= 100);
	}

	@Test
	@DisplayName("callers de spin : main, et uniquement main")
	void callersOfSpin() throws IOException {
		final ProfileTable callers = run.recording.table(run.scope(), ProfileViews.CALLERS, "Burner.spin", 10);

		assertEquals(List.of("Burner.main"), callers.rows().items().stream().map(ProfileRow::name).toList());
	}

	@Test
	@DisplayName("callees de spin : hash, que spin appelle")
	void calleesOfSpin() throws IOException {
		final ProfileTable callees = run.recording.table(run.scope(), ProfileViews.CALLEES, "Burner.spin", 10);

		assertTrue(callees.rows().items().stream().anyMatch(row -> row.name().equals("Burner.hash")),
				callees.toString());
	}

	@Test
	@DisplayName("callers refuse « * » : sans méthode, la question n'en est pas une")
	void callersNeedAMethod() {
		assertThrows(IllegalArgumentException.class,
				() -> run.recording.table(run.scope(), ProfileViews.CALLERS, ProfileViews.NO_FILTER, 10));
	}

	@Test
	@DisplayName("un filtre qui ne correspond à rien rend une table vide, pas une erreur")
	void unmatchedFilterIsEmpty() throws IOException {
		final ProfileTable none = run.recording.table(run.scope(), ProfileViews.HOT, "PasDansLeProjet", 10);

		assertTrue(none.rows().items().isEmpty());
		assertEquals(0, none.rows().totalCount());
	}

	@Test
	@DisplayName("une vue inconnue est refusée en listant les vues")
	void unknownViewIsRefused() {
		final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> run.recording.table(run.scope(), "chaud", ProfileViews.NO_FILTER, 10));

		assertTrue(refused.getMessage().contains("callers"), refused.getMessage());
	}

	@Test
	@DisplayName("hors du périmètre, rien n'est au projet : tous les échantillons sont « sans frame du projet »")
	void emptyScopeAttributesNothing(@TempDir final Path elsewhere) throws IOException {
		// Un projet dont les sources ne contiennent pas Burner.java : la même
		// enregistrement, lu avec ce périmètre, n'a aucune frame du projet.
		final ProfileScope nothing = ProfileScope.of(elsewhere, false);

		final ProfileOverview overview = run.recording.overview(nothing);

		assertEquals(overview.cpuSamples(), overview.samplesOutOfScope());
		assertTrue(run.recording.table(nothing, ProfileViews.HOT, ProfileViews.NO_FILTER, 10).rows().items().isEmpty());
	}

	@Test
	@DisplayName("un enregistrement qui n'est pas du JFR est un IOException qui le dit")
	void garbageIsAnIoException(@TempDir final Path dir) throws IOException {
		final Path garbage = dir.resolve("x.jfr");
		Files.writeString(garbage, "ceci n'est pas un enregistrement");

		assertThrows(IOException.class, () -> JfrAnalyzer.analyze(List.of(garbage), run.scope()));
		assertFalse(Files.size(garbage) == 0);
	}

}
