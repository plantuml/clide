package clide.command.testrun;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import java.util.ArrayList;
import java.util.List;

import clide.model.Listing;
import clide.model.TestMeasure;
import clide.model.TestOutcome;
import clide.util.Units;

/**
 * How a test run reads. Shared by run_test and run_tests, which differ in what
 * they select and in how long they may take, never in how a result looks.
 *
 * The totals line first, then one entry per test - failures with their message
 * indented under them, and the place the exception actually came from when that
 * is not the test's own line. A test that ran carries what it cost in
 * parentheses after its name: time, CPU time, bytes allocated and, only when
 * there were some, the collections. A cost that was not measured is left out
 * rather than printed as zero, which would read as "free".
 */
final class TestRunRendering {

	private TestRunRendering() {
	}

	static String render(final String label, final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.TestRun run -> {
			final StringBuilder out = new StringBuilder();
			out.append(label).append(": ").append(run.total()).append(" test(s), ").append(run.passed())
					.append(" passed, ").append(run.failed()).append(" failed");
			if (run.skipped() > 0)
				out.append(", ").append(run.skipped()).append(" skipped");

			out.append(" in ").append(run.elapsedMillis()).append(" ms");
			if (run.order().equals("time"))
				out.append(", listed slowest first");
			else if (run.order().equals("allocation"))
				out.append(", listed by allocation, heaviest first");

			final Listing<TestOutcome> tests = run.tests();
			for (final TestOutcome test : tests.items())
				out.append('\n').append(entry(test));

			// Only ever said when it is true, and said against the listing rather than
			// against the run: the totals above already describe the whole run, so this
			// line is about what was left out of the listing and nothing else.
			if (tests.truncated())
				out.append('\n').append(label).append(": ").append(tests.summarize("entry"));

			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(label, result.payload());
		};
	}

	private static String entry(final TestOutcome test) {
		return switch (test.status()) {
		case PASSED -> "[passed] " + test.name() + cost(test.measure());
		case SKIPPED -> "[skipped] " + test.name() + ": " + String.join(" ", test.messageLines());
		case FAILED -> failure(test);
		};
	}

	/**
	 * " (12 ms, cpu 11 ms, alloc 3.2 MB, gc 2 x 8 ms)", or "" when nothing was
	 * measured. Each part is left out when its reading is missing, and gc when no
	 * collection happened during the test, which is the usual case.
	 */
	static String cost(final TestMeasure measure) {
		final List<String> parts = new ArrayList<>();
		if (measure.wallNanos() >= 0)
			parts.add(duration(measure.wallNanos()));

		if (measure.cpuNanos() >= 0)
			parts.add("cpu " + duration(measure.cpuNanos()));

		if (measure.allocatedBytes() >= 0)
			parts.add("alloc " + size(measure.allocatedBytes()));

		if (measure.gcCount() > 0)
			parts.add("gc " + measure.gcCount() + " x " + measure.gcMillis() + " ms");

		return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
	}

	/** "0.4 ms", "12 ms", "3.2 s": the unit that keeps two significant figures at least. */
	static String duration(final long nanos) {
		return Units.duration(nanos);
	}

	/** "812 B", "3.2 KB", "41.0 MB": binary multiples, one decimal. */
	static String size(final long bytes) {
		return Units.size(bytes);
	}

	private static String failure(final TestOutcome test) {
		final StringBuilder out = new StringBuilder();
		out.append("[failed] ").append(test.location().isEmpty() ? test.name() : test.location()).append(": ")
				.append(test.name()).append(cost(test.measure()));
		for (final String line : test.messageLines())
			out.append("\n    ").append(line);

		if (test.origin().isEmpty() == false)
			out.append("\n    thrown at ").append(test.origin());

		return out.toString();
	}

}
