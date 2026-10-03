package clide.model;

/**
 * bench_test run twice - against a reference build and against the current one -
 * and what the difference is worth.
 *
 * The deltas are in percent of the reference median, negative when the current
 * build is lower (faster, lighter). noisePercent is the larger of the two runs'
 * spread, the yardstick a delta must beat; verdict is "slower", "faster" or
 * "same" on the wall-clock delta against that yardstick - see BenchCompare. A delta
 * is NaN when either side could not take the measure.
 */
public record Comparison(String reference, BenchReport referenceReport, BenchReport current, double wallDeltaPercent,
		double cpuDeltaPercent, double allocatedDeltaPercent, double noisePercent, String verdict) {
}
