package clide.command.testrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.PrintMode;
import clide.bench.BenchStats;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.lua.LuaPayloads;
import clide.model.BenchReport;
import clide.model.BenchStat;
import clide.model.Listing;
import clide.model.ProfileOverview;
import clide.model.ProfileRow;
import clide.model.ProfileTable;

/** Les trois commandes de benchmark : leurs arguments, leur rendu, leur forme en Lua. */
class BenchCommandsTest {

	private static BenchReport report(final long median) {
		return new BenchReport("demo.CalcTest#add", 5, 20,
				new BenchStat(median - 100_000, median, median + 200_000, median + 900_000),
				new BenchStat(1_000_000, 1_100_000, 1_300_000, 2_000_000),
				new BenchStat(40_960, 40_960, 40_960, 40_960), 3, 12);
	}

	@Test
	@DisplayName("bench_test imprime, par mesure, le minimum, la médiane, le p90 et le maximum, puis le GC et l'écart")
	void benchRendering() {
		final String text = new BenchTestCommand().render(CommandResult.ok(new CommandPayload.Bench(report(1_300_000))),
				PrintMode.AI);

		assertTrue(text.startsWith("bench_test: demo.CalcTest#add - 5 warmup, 20 measured iteration(s)\n"), text);
		assertTrue(text.contains("wall    min 1.2 ms   median 1.3 ms   p90 1.5 ms   max 2.2 ms\n"), text);
		assertTrue(text.contains("cpu     min 1.0 ms   median 1.1 ms   p90 1.3 ms   max 2.0 ms\n"), text);
		assertTrue(text.contains("alloc   min 40.0 KB   median 40.0 KB   p90 40.0 KB   max 40.0 KB\n"), text);
		assertTrue(text.contains("gc      3 collection(s), 12 ms, over the measured iterations\n"), text);
		assertTrue(text.contains("spread  23.1% between the best and the p90 wall-clock time"), text);
		assertTrue(text.contains("error   the median is known to wall \u00b1"), text);
	}

	@Test
	@DisplayName("une mesure que le JVM n'a pas pu prendre est dite telle, pas affichée à zéro")
	void unknownMeasureRendering() {
		final BenchReport unknownCpu = new BenchReport("demo.T#a", 0, 1, report(1_000_000).wall(), BenchStat.UNKNOWN,
				BenchStat.UNKNOWN, 0, 0);

		final String text = new BenchTestCommand().render(CommandResult.ok(new CommandPayload.Bench(unknownCpu)),
				PrintMode.AI);

		assertTrue(text.contains("cpu     not measurable on this JVM\n"), text);
		assertTrue(text.contains("alloc   not measurable on this JVM\n"), text);
	}

	@Test
	@DisplayName("compare_test met les deux médianes côte à côte, avec le delta, le bruit et le verdict")
	void compareRendering() {
		final CommandPayload.Compared compared = new CommandPayload.Compared(
				BenchStats.compare("ref.jar", report(2_000_000), report(1_000_000)));

		final String text = new CompareTestCommand().render(CommandResult.ok(compared), PrintMode.AI);

		assertTrue(text.startsWith("compare_test: demo.CalcTest#add - 5 warmup, 20 measured iteration(s), reference ref.jar\n"),
				text);
		assertTrue(text.contains("median  reference     current       delta    noise    verdict\n"), text);
		assertTrue(text.contains("wall    2.0 ms        1.0 ms        -50.0%   \u00b1"), text);
		assertTrue(text.contains("faster\n"), text);
		assertTrue(text.contains("alloc   40.0 KB       40.0 KB       +0.0%    \u00b11.0%    same"), text);
	}

	@Test
	@DisplayName("un test qui échoue arrête le benchmark, et c'est dit comme run_test le dit, pas une erreur interne")
	void failedTestEndsTheBenchmark() {
		final CommandPayload.TestRun run = new CommandPayload.TestRun("demo.CalcTest", 0, 1, 0, 42, Listing.of(
				List.of(new clide.model.TestOutcome(clide.model.TestOutcome.Status.FAILED, "demo.CalcTest.add",
						"src/test/java/demo/CalcTest.java:4", List.of("boom"), "", clide.model.TestMeasure.UNKNOWN)),
				1), true, "");

		final String text = new BenchTestCommand().render(
				CommandResult.error(clide.command.answer.ErrorCode.TEST_FAILURES, "1 test(s) failed out of 1", "", run), PrintMode.AI);

		assertTrue(text.contains("bench_test: 1 test(s), 0 passed, 1 failed"), text);
		assertTrue(text.contains("boom"), text);
	}

	@Test
	@DisplayName("profile_bench met le benchmark en tête, puis le profil tel que profile_test l'imprime")
	void profileBenchRendering() {
		final ProfileTable hot = new ProfileTable("hot", "samples", 200,
				Listing.of(List.of(new ProfileRow(60, 30.0, "src/main/java/demo/Calc.java", "Calc.add")), 10));
		final CommandPayload.Profile profile = new CommandPayload.Profile(
				new ProfileOverview(500, 200, 20, 2, 9, 5, 3 * 1024 * 1024, 4, 0), List.of(hot));

		final String text = new ProfileBenchCommand().render(
				CommandResult.ok(new CommandPayload.BenchProfiled(report(1_300_000), profile)), PrintMode.AI);

		assertTrue(text.startsWith("profile_bench: demo.CalcTest#add"), text);
		assertTrue(text.indexOf("== overview") > text.indexOf("spread"), text);
		assertTrue(text.contains("        60  30.0%  src/main/java/demo/Calc.java  Calc.add"), text);
	}

	@Test
	@DisplayName("<warmup> et <iterations> sont bornés, et 0 itération mesurée est refusée")
	void arguments() {
		assertNull(BenchArguments.parse("0", "1").error);
		assertNull(BenchArguments.parse("1000", "1000").error);
		assertEquals(5, BenchArguments.parse("5", "20").warmup);
		assertEquals(20, BenchArguments.parse("5", "20").iterations);

		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, BenchArguments.parse("5", "0").error.code());
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, BenchArguments.parse("1001", "5").error.code());
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, BenchArguments.parse("5", "1001").error.code());
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, BenchArguments.parse("99999999999", "5").error.code());
	}

	@Test
	@DisplayName("compare_test refuse une référence qui n'existe pas, par son nom, avant de lancer quoi que ce soit")
	void missingReference(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path root) {
		final clide.core.ClideContext context = new clide.core.ClideContext(
				new clide.core.FilesRepository(root, null), null, List.of());

		final CommandResult result = new CompareTestCommand().executeCommand(context, "unused", "1", "1", "no-such.jar");

		assertEquals(ErrorCode.FILE_NOT_FOUND, result.code());
		assertTrue(result.message().contains("no-such.jar"), result.message());
	}

	@Test
	@DisplayName("en Lua : statistiques par mesure, et les deltas du comparatif, NaN omis")
	@SuppressWarnings("unchecked")
	void luaShape() {
		final Map<String, Object> bench = (Map<String, Object>) LuaPayloads
				.toLua(new CommandPayload.Bench(report(1_300_000)));
		final Map<String, Object> inner = (Map<String, Object>) bench.get("report");
		assertEquals(20L, inner.get("iterations"));
		assertEquals(1_300_000L, ((Map<String, Object>) inner.get("wall")).get("median"));

		final BenchReport unknownCpu = new BenchReport("demo.T#a", 0, 1, report(1_000_000).wall(), BenchStat.UNKNOWN,
				BenchStat.UNKNOWN, 0, 0);
		final Map<String, Object> compared = (Map<String, Object>) LuaPayloads.toLua(
				new CommandPayload.Compared(BenchStats.compare("ref.jar", unknownCpu, unknownCpu)));
		assertEquals("same", compared.get("verdict"));
		assertNotNull(compared.get("wallDeltaPercent"));
		assertNull(compared.get("cpuDeltaPercent"));
		assertEquals("unknown", compared.get("cpuVerdict"));
		assertEquals("unknown", compared.get("allocatedVerdict"));
	}

}
