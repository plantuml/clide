package clide.command;

import clide.result.CommandPayload;
import clide.result.CommandResult;
import clide.result.Listing;
import clide.result.TestOutcome;

/**
 * How a test run reads. Shared by run_test and run_tests, which differ in what
 * they select and in how long they may take, never in how a result looks.
 *
 * The totals line first, then one entry per test - failures with their message
 * indented under them, and the place the exception actually came from when that
 * is not the test's own line.
 */
final class TestRunRendering {

	private TestRunRendering() {
	}

	static String render(final String label, final CommandResult result) {
		if (result.payload() instanceof CommandPayload.TestRun run) {
			final StringBuilder out = new StringBuilder();
			out.append(label).append(": ").append(run.total()).append(" test(s), ").append(run.passed())
					.append(" passed, ").append(run.failed()).append(" failed");
			if (run.skipped() > 0)
				out.append(", ").append(run.skipped()).append(" skipped");

			out.append(" in ").append(run.elapsedMillis()).append(" ms");

			final Listing<TestOutcome> tests = run.tests();
			for (final TestOutcome test : tests.items())
				out.append('\n').append(entry(test));

			// Only ever said when it is true, and said against the listing rather than
			// against the run: the totals above already describe the whole run, so this
			// line is about what was left out of the listing and nothing else.
			if (tests.truncated())
				out.append('\n').append(label).append(": ").append(tests.summarize("entry"));

			return out.toString();
		}

		return "";
	}

	private static String entry(final TestOutcome test) {
		return switch (test.status()) {
		case PASSED -> "[passed] " + test.name();
		case SKIPPED -> "[skipped] " + test.name() + ": " + String.join(" ", test.messageLines());
		case FAILED -> failure(test);
		};
	}

	private static String failure(final TestOutcome test) {
		final StringBuilder out = new StringBuilder();
		out.append("[failed] ").append(test.location().isEmpty() ? test.name() : test.location()).append(": ")
				.append(test.name());
		for (final String line : test.messageLines())
			out.append("\n    ").append(line);

		if (test.origin().isEmpty() == false)
			out.append("\n    thrown at ").append(test.origin());

		return out.toString();
	}

}
