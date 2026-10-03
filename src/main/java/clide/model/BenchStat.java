package clide.model;

/**
 * The spread of one measure over the measured iterations of a benchmark: the
 * best case, the middle, the bad tail and the worst. Nanoseconds for a time,
 * bytes for an allocation.
 *
 * The median is what two runs are compared on, the minimum is what the code can
 * do when nothing else gets in the way, and the p90 is how far a bad iteration
 * strays - together they say whether a difference means anything, which one mean
 * would not. A measure the JVM could not take is UNKNOWN, all four at -1, never
 * zeros that read as "free".
 */
public record BenchStat(long min, long median, long p90, long max) {

	public static final BenchStat UNKNOWN = new BenchStat(-1, -1, -1, -1);

	public boolean known() {
		return median >= 0;
	}

	/** How far the bad tail strays from the best case, in percent of the median - 0 when unknown or free. */
	public double spreadPercent() {
		return known() && median > 0 ? 100.0 * (p90 - min) / median : 0;
	}

}
