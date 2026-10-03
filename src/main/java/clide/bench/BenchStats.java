package clide.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import clide.model.BenchReport;
import clide.model.BenchStat;
import clide.model.Comparison;
import clide.model.TestMeasure;
import clide.test.TestMeter;
import clide.test.TestRunnerMain;

/**
 * Turns what the test JVM measured - one ITER record per iteration, see
 * TestRunnerMain - into the numbers bench_test and compare_test print.
 * Pure: no JVM, no jdtls.
 */
public final class BenchStats {

	/** A delta smaller than this is never reported as a change, however quiet the runs were. */
	public static final double NOISE_FLOOR_PERCENT = 2.0;

	private BenchStats() {
	}

	/** One finished iteration: whether it was a warmup, and what it cost. */
	public record Iteration(boolean warmup, TestMeasure measure) {
	}

	/**
	 * The ITER records among records, in the order they were written; anything
	 * that is not one, or is cut short, is ignored.
	 */
	public static List<Iteration> iterations(final List<String> records) {
		final List<Iteration> found = new ArrayList<>();
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			if (fields.size() < 3 || fields.get(0).equals(TestRunnerMain.ITER) == false)
				continue;

			final TestMeasure measure = TestMeter.parse(fields, 3);
			if (measure.known())
				found.add(new Iteration(fields.get(2).equals(TestRunnerMain.WARMUP), measure));
		}
		return found;
	}

	/** The report of the measured iterations; null when there are none. */
	public static BenchReport report(final String subject, final int warmup, final List<Iteration> iterations) {
		final List<TestMeasure> measured = new ArrayList<>();
		for (final Iteration iteration : iterations)
			if (iteration.warmup() == false)
				measured.add(iteration.measure());

		if (measured.isEmpty())
			return null;

		long gcCount = 0;
		long gcMillis = 0;
		for (final TestMeasure measure : measured) {
			gcCount += Math.max(0, measure.gcCount());
			gcMillis += Math.max(0, measure.gcMillis());
		}

		return new BenchReport(subject, warmup, measured.size(), stat(measured, TestMeasure::wallNanos),
				stat(measured, TestMeasure::cpuNanos), stat(measured, TestMeasure::allocatedBytes), gcCount, gcMillis);
	}

	private static BenchStat stat(final List<TestMeasure> measures,
			final java.util.function.ToLongFunction<TestMeasure> reading) {
		final long[] values = new long[measures.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = reading.applyAsLong(measures.get(i));
			if (values[i] < 0)
				return BenchStat.UNKNOWN;
		}
		return of(values);
	}

	/**
	 * min, median (the mean of the two middle values when there is an even count),
	 * p90 (nearest rank) and max of values; UNKNOWN for none.
	 */
	public static BenchStat of(final long[] values) {
		if (values.length == 0)
			return BenchStat.UNKNOWN;

		final long[] sorted = values.clone();
		Arrays.sort(sorted);
		final int n = sorted.length;
		final long median = n % 2 == 1 ? sorted[n / 2] : sorted[n / 2 - 1] + (sorted[n / 2] - sorted[n / 2 - 1]) / 2;
		final int p90Rank = (int) Math.ceil(0.9 * n);
		return new BenchStat(sorted[0], median, sorted[Math.max(1, p90Rank) - 1], sorted[n - 1]);
	}

	/** (current - reference) / reference, in percent; NaN when either is unknown or the reference is 0. */
	public static double delta(final BenchStat reference, final BenchStat current) {
		if (reference.known() == false || current.known() == false || reference.median() == 0)
			return Double.NaN;

		return 100.0 * (current.median() - reference.median()) / reference.median();
	}

	/**
	 * The verdict on the wall-clock medians: "slower" or "faster" only when the
	 * delta beats both runs' own spread and the noise floor, "same" otherwise - a
	 * delta inside the noise is not a finding. "unknown" when the wall-clock delta
	 * cannot be computed.
	 */
	public static Comparison compare(final String reference, final BenchReport referenceReport,
			final BenchReport current) {
		final double wall = delta(referenceReport.wall(), current.wall());
		final double noise = Math.max(Math.max(referenceReport.wall().spreadPercent(), current.wall().spreadPercent()),
				NOISE_FLOOR_PERCENT);
		final String verdict;
		if (Double.isNaN(wall))
			verdict = "unknown";
		else if (Math.abs(wall) <= noise)
			verdict = "same";
		else
			verdict = wall > 0 ? "slower" : "faster";

		return new Comparison(reference, referenceReport, current, wall,
				delta(referenceReport.cpu(), current.cpu()), delta(referenceReport.allocated(), current.allocated()),
				noise, verdict);
	}

}
