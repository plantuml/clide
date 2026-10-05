package clide.command.testrun;

import java.util.Locale;
import java.util.function.LongFunction;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.bench.BenchStats;
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

	/** Above this wall-clock noise a comparison is told that more iterations would help. */
	private static final double NOISY_PERCENT = 10.0;

	private BenchRendering() {
	}

	/** bench_test. */
	static String renderBench(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Bench bench -> report(label, bench.report());
		// A test that failed ends the benchmark: said the way run_test says it.
		case CommandPayload.TestRun run -> TestRunRendering.render(label, result);
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/** profile_bench: the benchmark, then the profile as profile_test prints it. */
	static String renderBenchProfiled(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.BenchProfiled bench -> report(label, bench.report()) + "\n\n"
				+ ProfileRendering.summary(bench.profile());
		case CommandPayload.TestRun run -> TestRunRendering.render(label, result);
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/** compare_test. */
	static String renderCompared(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Compared compared -> comparison(label, compared.comparison());
		case CommandPayload.TestRun run -> TestRunRendering.render(label, result);
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
				+ " between the best and the p90 wall-clock time: a difference below that between two runs means little\n" //
				+ "error   the median is known to " + errors(report);
	}

	/** What the median of each measure is worth: twice its standard error, in percent of itself. */
	private static String errors(final BenchReport report) {
		final StringBuilder out = new StringBuilder();
		append(out, "wall", report.wall(), report.iterations());
		append(out, "cpu", report.cpu(), report.iterations());
		append(out, "alloc", report.allocated(), report.iterations());
		return out.append(" (twice the standard error, ").append(report.iterations()).append(" iterations)").toString();
	}

	private static void append(final StringBuilder out, final String name, final BenchStat stat, final int n) {
		if (stat.known() == false || stat.median() == 0)
			return;

		if (out.length() > 0)
			out.append(", ");
		out.append(name).append(String.format(Locale.ROOT, " \u00b1%.1f%%", 200.0 * stat.medianError(n) / stat.median()));
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
		final StringBuilder out = new StringBuilder();
		out.append(label).append(": ").append(current.subject()).append(" - ").append(iterations(current))
				.append(", reference ").append(comparison.reference()).append('\n');
		out.append(String.format(Locale.ROOT, "%-8s%-14s%-14s%-9s%-9s%s", "median", "reference", "current", "delta",
				"noise", "verdict")).append('\n');
		out.append(row("wall", reference.wall(), current.wall(), comparison.wall(), Units::duration)).append('\n');
		out.append(row("cpu", reference.cpu(), current.cpu(), comparison.cpu(), Units::duration)).append('\n');
		out.append(row("alloc", reference.allocated(), current.allocated(), comparison.allocated(), Units::size))
				.append('\n');
		out.append("noise   the smallest delta that is not noise: twice the standard error of the two medians, ")
				.append("never less than ").append(percent(BenchStats.NOISE_FLOOR_PERCENT)).append(" (alloc ")
				.append(percent(BenchStats.ALLOCATION_NOISE_FLOOR_PERCENT)).append(")\n");
		if (comparison.wall().noisePercent() > NOISY_PERCENT)
			out.append("hint    the wall-clock noise is ").append(percent(comparison.wall().noisePercent()))
					.append(": more iterations (and warmup) narrow it; the alloc verdict does not depend on it\n");

		return out.toString().stripTrailing();
	}

	private static String row(final String name, final BenchStat reference, final BenchStat current,
			final Comparison.Metric metric, final LongFunction<String> unit) {
		final double delta = metric.deltaPercent();
		return String.format(Locale.ROOT, "%-8s%-14s%-14s%-9s%-9s%s", name,
				reference.known() ? unit.apply(reference.median()) : "-",
				current.known() ? unit.apply(current.median()) : "-",
				Double.isNaN(delta) ? "n/a" : String.format(Locale.ROOT, "%+.1f%%", delta),
				Double.isNaN(delta) ? "-" : String.format(Locale.ROOT, "\u00b1%.1f%%", metric.noisePercent()),
				metric.verdict());
	}

	private static String percent(final double value) {
		return String.format(Locale.ROOT, "%.1f%%", value);
	}

}
