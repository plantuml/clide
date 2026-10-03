package clide.command.testrun;

import java.util.Map;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.model.Listing;
import clide.model.ProfileOverview;
import clide.model.ProfileRow;
import clide.model.ProfileTable;
import clide.profile.ProfileViews;
import clide.util.Units;

/**
 * How a profile reads: the numbers that frame it, then ranked tables whose rows
 * are "path:line  Class.method" - the notation read_lines takes, so a hotspot
 * pastes straight into the next question.
 *
 * Shared by profile_test, profile_tests (which put the tests' verdict first, as
 * run_test prints it) and profile_report.
 */
final class ProfileRendering {

	private static final Map<String, String> TITLES = Map.of( //
			ProfileViews.HOT, "cpu: attributed to a project method (its own code plus the library code it calls)", //
			ProfileViews.INCLUSIVE, "cpu: inclusive (project method anywhere on the stack)", //
			ProfileViews.SELF, "cpu: self (the leaf frame is project code)", //
			ProfileViews.LINES, "cpu: hot lines (the project line responsible for the sample)", //
			ProfileViews.JDK, "cpu: library leaf  <-  project caller", //
			ProfileViews.ALLOC, "alloc: by project site (sampled weight)", //
			ProfileViews.ALLOC_TYPES, "alloc: by type (sampled weight)", //
			ProfileViews.CONTENTION, "contention: time blocked or parked, by project site", //
			ProfileViews.CALLERS, "callers: project methods calling the one asked about", //
			ProfileViews.CALLEES, "callees: project methods called by the one asked about");

	private ProfileRendering() {
	}

	/** profile_test / profile_tests: the verdict as run_test prints it, then the profile. */
	static String renderProfiled(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Profiled profiled -> TestRunRendering.render(label, CommandResult.ok(profiled.run()))
				+ "\n" + profile(profiled.profile(), true);
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/** profile_report. */
	static String renderReport(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Profile profile -> profile(profile, false);
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	/**
	 * summary tables are the short ones profile_test leads with: when one is cut, the
	 * way to more is profile_report, not set_max_results, which would not widen them.
	 */
	private static String profile(final CommandPayload.Profile profile, final boolean summary) {
		final StringBuilder out = new StringBuilder(overview(profile.overview()));
		for (final ProfileTable table : profile.tables())
			out.append("\n\n").append(table(table, summary));

		return out.toString();
	}

	private static String overview(final ProfileOverview overview) {
		return "== overview\n" //
				+ "recording     " + overview.recordingMillis() + " ms\n" //
				+ "cpu samples   " + overview.cpuSamples() + "  (" + overview.samplesOutOfScope()
				+ " with no frame in the profiled code: harness, JUnit or library-only stacks)\n" //
				+ "gc            " + overview.gcCount() + " collection(s), " + overview.gcPausedMillis()
				+ " ms paused in total, longest " + overview.gcLongestMillis() + " ms\n" //
				+ "allocation    ~" + Units.size(overview.allocatedBytes()) + " sampled weight\n" //
				+ "exceptions    " + overview.exceptions() + " thrown\n" //
				+ "contention    " + overview.contentionMillis() + " ms blocked or parked in project frames";
	}

	private static String table(final ProfileTable table, final boolean summary) {
		final StringBuilder out = new StringBuilder("== ").append(TITLES.getOrDefault(table.view(), table.view()));
		final Listing<ProfileRow> rows = table.rows();
		if (rows.items().isEmpty())
			out.append("\n   (nothing)");

		for (final ProfileRow row : rows.items())
			out.append('\n').append(String.format("%10s %5.1f%%  ", value(table.unit(), row.value()), row.percent()))
					.append(row.location().isEmpty() ? "" : row.location() + "  ").append(row.name());

		if (rows.truncated())
			out.append('\n').append(summary ? rows.returnedCount() + " row(s) shown out of " + rows.totalCount()
					+ " - profile_report " + table.view() + " * lists more" : rows.summarize("row"));

		return out.toString();
	}

	private static String value(final String unit, final long value) {
		return switch (unit) {
		case "bytes" -> Units.size(value);
		case "ms" -> value + " ms";
		default -> Long.toString(value);
		};
	}

}
