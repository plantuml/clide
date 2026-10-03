package clide.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.model.TestMeasure;

/**
 * Tests de TestMeter : le transport des mesures sur le protocole, et le fait
 * qu'une mesure réelle voit ce qui s'est passé entre start() et stop().
 */
class TestMeterTest {

	@Test
	@DisplayName("une mesure fait l'aller-retour sur les champs d'un enregistrement")
	void measureSurvivesTheProtocol() {
		final TestMeasure measure = new TestMeasure(5_000_000, 4_000_000, 65_536, 1, 3);

		final List<String> record = new java.util.ArrayList<>(List.of("PASS", "demo.T", "a", "a()"));
		record.addAll(TestMeter.fields(measure));

		assertEquals(measure, TestMeter.parse(record, 4));
	}

	@Test
	@DisplayName("une lecture inconnue (-1) fait aussi l'aller-retour, sans devenir zéro")
	void unknownSurvivesTheProtocol() {
		assertEquals(TestMeasure.UNKNOWN, TestMeter.parse(TestMeter.fields(TestMeasure.UNKNOWN), 0));
	}

	@Test
	@DisplayName("deux mesures s'additionnent, et une lecture inconnue d'un côté reste inconnue dans la somme")
	void measuresAddUp() {
		final TestMeasure sum = new TestMeasure(10, 4, 100, 1, 2).plus(new TestMeasure(5, 3, 50, 0, 1));

		assertEquals(new TestMeasure(15, 7, 150, 1, 3), sum);
		assertEquals(-1, new TestMeasure(10, -1, 100, 0, 0).plus(new TestMeasure(5, 3, 50, 0, 0)).cpuNanos());
	}

	@Test
	@DisplayName("un enregistrement trop court ou illisible donne UNKNOWN plutôt qu'une exception")
	void shortOrGarbledRecordIsUnknown() {
		assertEquals(TestMeasure.UNKNOWN, TestMeter.parse(List.of("PASS", "demo.T", "a", "a()"), 4));
		assertEquals(TestMeasure.UNKNOWN, TestMeter.parse(List.of("1", "2", "3", "4"), 0));
		assertEquals(TestMeasure.UNKNOWN, TestMeter.parse(List.of("1", "2", "trois", "4", "5"), 0));
	}

	@Test
	@DisplayName("le temps mural d'une attente se retrouve dans la mesure")
	void wallTimeSeesASleep() throws InterruptedException {
		final TestMeter.Reading reading = TestMeter.start();
		Thread.sleep(30);
		final TestMeasure measure = TestMeter.stop(reading);

		assertTrue(measure.known());
		assertTrue(measure.wallNanos() >= 25_000_000L, "mur : " + measure.wallNanos());
	}

	@Test
	@DisplayName("l'allocation d'un tableau se retrouve dans la mesure du thread courant")
	void allocationSeesAnArray() {
		final TestMeter.Reading reading = TestMeter.start();
		final byte[] kept = new byte[4 * 1024 * 1024];
		final TestMeasure measure = TestMeter.stop(reading);

		// -1 si cette JVM ne sait pas compter : on ne teste que quand elle le sait.
		if (measure.allocatedBytes() >= 0)
			assertTrue(measure.allocatedBytes() >= kept.length, "alloué : " + measure.allocatedBytes());

		assertTrue(measure.cpuNanos() >= -1);
		assertTrue(measure.gcCount() >= -1);
	}

}
