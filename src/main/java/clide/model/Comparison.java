package clide.model;

/**
 * bench_test run twice - against a reference build and against the current one -
 * and what the difference is worth, measure by measure.
 *
 * Each measure has its own verdict, because they do not share a noise: an
 * allocation is counted exactly and a delta of a few percent is real, a wall-clock
 * time moves with the machine. The deltas are in percent of the reference median,
 * negative when the current build is lower (faster, lighter); a delta is NaN when
 * either side could not take the measure.
 *
 * wall(), cpu() and allocated() carry the delta, the noise it had to beat and the
 * verdict; the shortcuts below are the wall-clock ones, the first thing a script
 * guards on.
 */
public record Comparison(String reference, BenchReport referenceReport, BenchReport current, Metric wall, Metric cpu,
		Metric allocated) {

	/**
	 * One measure of the comparison: deltaPercent (NaN if unmeasurable), noisePercent
	 * (the smallest delta that is not noise) and verdict - "slower" or "faster" when
	 * the delta beats the noise, "same" when it does not, "unknown" when it cannot be
	 * computed.
	 */
	public record Metric(double deltaPercent, double noisePercent, String verdict) {
	}

	public double wallDeltaPercent() {
		return wall.deltaPercent();
	}

	public double cpuDeltaPercent() {
		return cpu.deltaPercent();
	}

	public double allocatedDeltaPercent() {
		return allocated.deltaPercent();
	}

	public double noisePercent() {
		return wall.noisePercent();
	}

	public String verdict() {
		return wall.verdict();
	}

}
