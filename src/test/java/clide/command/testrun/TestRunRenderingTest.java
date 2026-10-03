package clide.command.testrun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.model.TestMeasure;

/**
 * Tests de la mise en forme des coûts d'un test.
 *
 * L'enjeu est qu'un coût inconnu ne se lise jamais comme un coût nul : « 0 B
 * alloués » dit qu'un test est gratuit, « rien » dit qu'on ne sait pas.
 */
class TestRunRenderingTest {

	@Test
	@DisplayName("sans aucune mesure, rien n'est ajouté à la ligne")
	void nothingMeasuredPrintsNothing() {
		assertEquals("", TestRunRendering.cost(TestMeasure.UNKNOWN));
	}

	@Test
	@DisplayName("temps, CPU et allocation, et pas de GC quand il n'y en a pas eu")
	void fullMeasureWithoutGc() {
		assertEquals(" (12 ms, cpu 11 ms, alloc 3.2 MB)",
				TestRunRendering.cost(new TestMeasure(12_000_000, 11_000_000, 3_355_443, 0, 0)));
	}

	@Test
	@DisplayName("le GC n'apparaît que s'il y a eu au moins une collection")
	void gcAppearsOnlyWhenItHappened() {
		assertEquals(" (30 ms, cpu 29 ms, alloc 1.0 KB, gc 2 x 8 ms)",
				TestRunRendering.cost(new TestMeasure(30_000_000, 29_000_000, 1024, 2, 8)));
	}

	@Test
	@DisplayName("une lecture manquante est omise, les autres restent")
	void missingReadingIsLeftOut() {
		assertEquals(" (4.0 ms, alloc 812 B)", TestRunRendering.cost(new TestMeasure(4_000_000, -1, 812, -1, -1)));
	}

	@Test
	@DisplayName("un test qui n'a rien alloué dit « 0 B », ce qui n'est pas « inconnu »")
	void zeroIsNotUnknown() {
		assertEquals(" (0.0 ms, cpu 0.0 ms, alloc 0 B)", TestRunRendering.cost(new TestMeasure(0, 0, 0, 0, 0)));
	}

	@Test
	@DisplayName("les durées gardent deux chiffres significatifs au moins")
	void durationUnits() {
		assertEquals("0.4 ms", TestRunRendering.duration(400_000));
		assertEquals("9.9 ms", TestRunRendering.duration(9_900_000));
		assertEquals("10 ms", TestRunRendering.duration(10_000_000));
		assertEquals("9999 ms", TestRunRendering.duration(9_999_000_000L));
		assertEquals("12.5 s", TestRunRendering.duration(12_500_000_000L));
	}

	@Test
	@DisplayName("les tailles passent à l'unité du dessus à 1024")
	void sizeUnits() {
		assertEquals("0 B", TestRunRendering.size(0));
		assertEquals("1023 B", TestRunRendering.size(1023));
		assertEquals("1.0 KB", TestRunRendering.size(1024));
		assertEquals("1.5 MB", TestRunRendering.size(1536L * 1024));
		assertEquals("2.0 GB", TestRunRendering.size(2L * 1024 * 1024 * 1024));
	}

}
