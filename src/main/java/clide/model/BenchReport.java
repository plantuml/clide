package clide.model;

/**
 * What bench_test measured for one test (or one class): warmup iterations that
 * were run and thrown away, then iterations measured, in the same JVM.
 *
 * An iteration is everything the selection ran once - one test for a method, the
 * sum of its tests for a class - and its numbers are the ones a TestMeasure
 * carries: wall-clock, CPU and allocation of the thread that ran it, fixtures
 * included. gcCount and gcMillis are totals over the measured iterations only,
 * the whole JVM's collections.
 */
public record BenchReport(String subject, int warmup, int iterations, BenchStat wall, BenchStat cpu,
		BenchStat allocated, long gcCount, long gcMillis) {

	public BenchReport {
		if (iterations < 1)
			throw new IllegalArgumentException("a benchmark measures at least one iteration");

		if (warmup < 0)
			throw new IllegalArgumentException("warmup must not be negative");
	}

}
