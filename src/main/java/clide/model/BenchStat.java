package clide.model;

/**
 * The spread of one measure over the measured iterations of a benchmark: the
 * best case, the quartiles around the middle, the bad tail and the worst. Nanoseconds for a time,
 * bytes for an allocation.
 *
 * The median is what two runs are compared on, the minimum is what the code can
 * do when nothing else gets in the way, and the p90 is how far a bad iteration
 * strays - together they say whether a difference means anything, which one mean
 * would not. The quartiles measure the noise of the middle itself, without being
 * moved by the one iteration a garbage collection or a busy neighbour spoiled. A measure the JVM could not take is UNKNOWN, all four at -1, never
 * zeros that read as "free".
 */
public record BenchStat(long min, long q1, long median, long q3, long p90, long max) {

	public static final BenchStat UNKNOWN = new BenchStat(-1, -1, -1, -1, -1, -1);

	/** A spread known only by its extremes: the quartiles are taken to be the min and the p90. */
	public BenchStat(final long min, final long median, final long p90, final long max) {
		this(min, min, median, p90, p90, max);
	}

	public boolean known() {
		return median >= 0;
	}

	/** How far the bad tail strays from the best case, in percent of the median - 0 when unknown or free. */
	public double spreadPercent() {
		return known() && median > 0 ? 100.0 * (p90 - min) / median : 0;
	}

	/**
	 * How far the median itself can be trusted, in the unit of the measure: the
	 * standard error of a median of n values, 1.2533 x sigma / sqrt(n), with sigma
	 * read off the interquartile range (IQR / 1.349 for a normal law). 0 when
	 * unknown.
	 */
	public double medianError(final int n) {
		if (known() == false || n < 1)
			return 0;

		return 0.929 * (q3 - q1) / Math.sqrt(n);
	}

}
