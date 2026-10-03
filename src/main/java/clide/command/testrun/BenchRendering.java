package clide.command.testrun;

import java.util.Locale;
import java.util.function.LongFunction;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.model.BenchReport;
import clide.model.BenchStat;
import clide.model.Comparison;
import clide.util.Units;

/**
 * How a benchmark reads: for each measure the best case, the median, the bad tail
 * and the worst, and - for a comparison - the two medians side by side with the
 * delta and the noise it has to beat.
 */
final class BenchRendering {

	private BenchRendering() {
	}

	/** bench_test. */
	static String renderBench(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Bench bench -> report(label, bench.report());
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/** profile_bench: the benchmark, then the profile as profile_test prints it. */
	static String renderBenchProfiled(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.BenchProfiled bench -> report(label, bench.report()) + "\n\n"
				+ ProfileRendering.summary(bench.profile());
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/** compare_test. */
	static String renderCompared(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Compared compared -> comparison(label, compared.comparison());
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	private static String report(final String label, final BenchReport report) {
		return label + ": " + report.subject() + " - " + iterations(report) + "\n" //
				+ line("wall", report.wall(), Units::duration) + "\n" //
				+ line("cpu", report.cpu(), Units::duration) + "\n" //
				+ line("alloc", report.allocated(), Units::size) + "\n" //
				+ "gc      " + report.gcCount() + " collection(s), " + report.gcMillis()
				+ " ms, over the measured iterations\n" //
				+ "spread  " + percent(report.wall().spreadPercent())
				+ " between the best and the p90 wall-clock time: a difference below that between two runs means little";
	}

	private static String iterations(final BenchReport report) {
		return report.warmup() + " warmup, " + report.iterations() + " measured iteration(s)";
	}

	private static String line(final String name, final BenchStat stat, final LongFunction<String> unit) {
		final String head = String.format(Locale.ROOT, "%-8s", name);
		if (stat.known() == false)
			return head + "not measurable on this JVM";

		return head + "min " + unit.apply(stat.min()) + "   median " + unit.apply(stat.median()) + "   p90 "
				+ unit.apply(stat.p90()) + "   max " + unit.apply(stat.max());
	}

	private static String comparison(final String label, final Comparison comparison) {
		final BenchReport reference = comparison.referenceReport();
		final BenchReport current = comparison.current();
		return label + ": " + current.subject() + " - " + iterations(current) + ", reference " + comparison.reference()
				+ "\n" //
				+ String.format(Locale.ROOT, "%-8s%-14s%-14s%s", "median", "reference", "current", "delta") + "\n" //
				+ row("wall", reference.wall(), current.wall(), comparison.wallDeltaPercent(), Units::duration) + "\n" //
				+ row("cpu", reference.cpu(), current.cpu(), comparison.cpuDeltaPercent(), Units::duration) + "\n" //
				+ row("alloc", reference.allocated(), current.allocated(), comparison.allocatedDeltaPercent(),
						Units::size)
				+ "\n" //
				+ "noise   " + percent(comparison.noisePercent())
				+ " (the larger spread of the two runs, never less than 2%)\n" //
				+ "verdict " + verdict(comparison);
	}

	private static String row(final String name, final BenchStat reference, final BenchStat current,
			final double delta, final LongFunction<String> unit) {
		return String.format(Locale.ROOT, "%-8s%-14s%-14s%s", name,
				reference.known() ? unit.apply(reference.median()) : "-",
				current.known() ? unit.apply(current.median()) : "-",
				Double.isNaN(delta) ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", delta));
	}

	private static String verdict(final Comparison comparison) {
		return switch (comparison.verdict()) {
		case "slower" -> "slower - the wall-clock median grew by more than the noise";
		case "faster" -> "faster - the wall-clock median shrank by more than the noise";
		case "same" -> "same - the wall-clock median moved by no more than the noise";
		default -> "unknown - the wall-clock times cannot be compared";
		};
	}

	private static String percent(final double value) {
		return String.format(Locale.ROOT, "%.1f%%", value);
	}

}
