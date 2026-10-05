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

	/** A time delta smaller than this is never reported as a change, however quiet the runs were. */
	public static final double NOISE_FLOOR_PERCENT = 2.0;

	/** The same for an allocation, which is counted exactly and moves much less from run to run. */
	public static final double ALLOCATION_NOISE_FLOOR_PERCENT = 1.0;

	/** How many standard errors of the difference a delta must exceed: about 95 % of confidence. */
	private static final double SIGNIFICANCE = 2.0;

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
		return new BenchStat(sorted[0], nearestRank(sorted, 0.25), median, nearestRank(sorted, 0.75),
				nearestRank(sorted, 0.9), sorted[n - 1]);
	}

	private static long nearestRank(final long[] sorted, final double fraction) {
		final int rank = (int) Math.ceil(fraction * sorted.length);
		return sorted[Math.max(1, rank) - 1];
	}

	/** (current - reference) / reference, in percent; NaN when either is unknown or the reference is 0. */
	public static double delta(final BenchStat reference, final BenchStat current) {
		if (reference.known() == false || current.known() == false || reference.median() == 0)
			return Double.NaN;

		return 100.0 * (current.median() - reference.median()) / reference.median();
	}

	/**
	 * The comparison of two reports, a verdict per measure: "slower" or "faster"
	 * only when the delta of the medians beats the noise, "same" otherwise - a
	 * delta inside the noise is not a finding. "unknown" when the delta cannot be
	 * computed.
	 *
	 * The noise of a measure is twice the standard error of the difference of the
	 * two medians (see BenchStat.medianError), in percent of the reference median,
	 * and never less than the floor of the measure. It shrinks with the number of
	 * iterations, which the old spread between the best and the p90 never did.
	 */
	public static Comparison compare(final String reference, final BenchReport referenceReport,
			final BenchReport current) {
		return new Comparison(reference, referenceReport, current,
				metric(referenceReport.wall(), referenceReport.iterations(), current.wall(), current.iterations(),
						NOISE_FLOOR_PERCENT),
				metric(referenceReport.cpu(), referenceReport.iterations(), current.cpu(), current.iterations(),
						NOISE_FLOOR_PERCENT),
				metric(referenceReport.allocated(), referenceReport.iterations(), current.allocated(),
						current.iterations(), ALLOCATION_NOISE_FLOOR_PERCENT));
	}

	private static Comparison.Metric metric(final BenchStat reference, final int referenceIterations,
			final BenchStat current, final int currentIterations, final double floorPercent) {
		final double delta = delta(reference, current);
		if (Double.isNaN(delta))
			return new Comparison.Metric(delta, floorPercent, "unknown");

		final double error = Math.hypot(reference.medianError(referenceIterations),
				current.medianError(currentIterations));
		final double noise = Math.max(SIGNIFICANCE * 100.0 * error / reference.median(), floorPercent);
		final String verdict = Math.abs(delta) <= noise ? "same" : delta > 0 ? "slower" : "faster";
		return new Comparison.Metric(delta, noise, verdict);
	}

}
