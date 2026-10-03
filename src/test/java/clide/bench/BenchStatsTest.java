package clide.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.model.BenchReport;
import clide.model.BenchStat;
import clide.model.Comparison;
import clide.model.TestMeasure;

/** Les chiffres d'un benchmark : médiane, p90, delta, verdict - de la pure arithmétique. */
class BenchStatsTest {

	@Test
	@DisplayName("min, médiane, p90 et max d'un nombre impair de valeurs, données dans le désordre")
	void oddCount() {
		final BenchStat stat = BenchStats.of(new long[] { 50, 10, 30, 20, 40 });

		assertEquals(new BenchStat(10, 30, 50, 50), stat);
	}

	@Test
	@DisplayName("la médiane d'un nombre pair de valeurs est la moyenne des deux du milieu")
	void evenCount() {
		assertEquals(25, BenchStats.of(new long[] { 40, 10, 20, 30 }).median());
	}

	@Test
	@DisplayName("le p90 est le rang le plus proche : sur dix valeurs, la neuvième")
	void p90IsNearestRank() {
		final long[] ten = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 100 };

		assertEquals(9, BenchStats.of(ten).p90());
		assertEquals(100, BenchStats.of(ten).max());
	}

	@Test
	@DisplayName("une seule valeur : tout vaut cette valeur, et l'écart est nul")
	void singleValue() {
		final BenchStat stat = BenchStats.of(new long[] { 7 });

		assertEquals(new BenchStat(7, 7, 7, 7), stat);
		assertEquals(0, stat.spreadPercent());
	}

	@Test
	@DisplayName("aucune valeur : inconnu, jamais zéro")
	void noValue() {
		assertEquals(BenchStat.UNKNOWN, BenchStats.of(new long[0]));
	}

	@Test
	@DisplayName("l'écart est la distance du p90 au minimum, en pourcentage de la médiane")
	void spread() {
		assertEquals(50.0, new BenchStat(100, 200, 200, 300).spreadPercent(), 1e-9);
	}

	@Test
	@DisplayName("les enregistrements ITER se lisent dans l'ordre, le reste est ignoré")
	void parsesIterations() {
		final List<String> records = List.of("SUMMARY\t1\t1\t0\t0\t10",
				"ITER\t0\tW\t9000000\t8000000\t1000\t0\t0", "ITER\t1\tM\t5000000\t4000000\t2000\t1\t3",
				"ITER\t2\tM\t6000000", "ITER\t3\tM\tx\ty\tz\t1\t2");

		final List<BenchStats.Iteration> found = BenchStats.iterations(records);

		assertEquals(2, found.size(), found.toString());
		assertTrue(found.get(0).warmup());
		assertEquals(new TestMeasure(5_000_000, 4_000_000, 2000, 1, 3), found.get(1).measure());
	}

	@Test
	@DisplayName("le rapport ne compte que les itérations mesurées, et somme les GC")
	void reportIgnoresWarmup() {
		final List<BenchStats.Iteration> iterations = List.of(
				new BenchStats.Iteration(true, new TestMeasure(900, 900, 900, 5, 50)),
				new BenchStats.Iteration(false, new TestMeasure(30, 20, 100, 1, 2)),
				new BenchStats.Iteration(false, new TestMeasure(10, 10, 300, 0, 0)),
				new BenchStats.Iteration(false, new TestMeasure(20, 15, 200, 2, 4)));

		final BenchReport report = BenchStats.report("demo.T#a", 1, iterations);

		assertEquals(3, report.iterations());
		assertEquals(1, report.warmup());
		assertEquals(new BenchStat(10, 20, 30, 30), report.wall());
		assertEquals(200, report.allocated().median());
		assertEquals(3, report.gcCount());
		assertEquals(6, report.gcMillis());
	}

	@Test
	@DisplayName("sans itération mesurée, pas de rapport")
	void noMeasuredIteration() {
		assertNull(BenchStats.report("demo.T#a", 2,
				List.of(new BenchStats.Iteration(true, new TestMeasure(1, 1, 1, 0, 0)))));
	}

	@Test
	@DisplayName("une lecture que le JVM n'a pas pu faire rend la statistique inconnue, pas fausse")
	void unknownReadingStaysUnknown() {
		final BenchReport report = BenchStats.report("demo.T#a", 0, List.of(
				new BenchStats.Iteration(false, new TestMeasure(10, -1, 5, 0, 0)),
				new BenchStats.Iteration(false, new TestMeasure(20, -1, 7, 0, 0))));

		assertEquals(BenchStat.UNKNOWN, report.cpu());
		assertTrue(report.wall().known());
	}

	private static BenchReport report(final long wallMedian, final long wallMin, final long wallP90) {
		final BenchStat wall = new BenchStat(wallMin, wallMedian, wallP90, wallP90);
		return new BenchReport("demo.T#a", 3, 10, wall, new BenchStat(1, 1, 1, 1), new BenchStat(100, 100, 100, 100), 0,
				0);
	}

	@Test
	@DisplayName("un delta au-delà du bruit est 'slower' ou 'faster', en deçà c'est 'same'")
	void verdict() {
		final BenchReport reference = report(1000, 980, 1000);

		assertEquals("slower", BenchStats.compare("ref.jar", reference, report(1200, 1180, 1200)).verdict());
		assertEquals("faster", BenchStats.compare("ref.jar", reference, report(800, 780, 800)).verdict());
		assertEquals("same", BenchStats.compare("ref.jar", reference, report(1010, 990, 1010)).verdict());
	}

	@Test
	@DisplayName("le delta est mesuré sur la référence, et le bruit ne tombe jamais sous 2 %")
	void deltaAndNoiseFloor() {
		final Comparison comparison = BenchStats.compare("ref.jar", report(1000, 1000, 1000), report(1100, 1100, 1100));

		assertEquals(10.0, comparison.wallDeltaPercent(), 1e-9);
		assertEquals(BenchStats.NOISE_FLOOR_PERCENT, comparison.noisePercent(), 1e-9);
	}

	@Test
	@DisplayName("un run bruyant relève le seuil : le même delta devient 'same'")
	void noisyRunRaisesTheBar() {
		// Écart (p90 - min) / médiane = 30 % : un delta de 10 % n'est pas une trouvaille.
		final BenchReport noisy = report(1000, 800, 1100);

		assertEquals("same", BenchStats.compare("ref.jar", noisy, report(1100, 1090, 1100)).verdict());
	}

	@Test
	@DisplayName("une référence à zéro ou inconnue donne NaN et le verdict 'unknown'")
	void unmeasurable() {
		final BenchReport zero = report(0, 0, 0);

		final Comparison comparison = BenchStats.compare("ref.jar", zero, report(10, 10, 10));

		assertTrue(Double.isNaN(comparison.wallDeltaPercent()));
		assertEquals("unknown", comparison.verdict());
	}

}
