package clide.result;

import java.util.List;

/**
 * What happened to one test. location is "path:line" when clide could place the
 * test through jdtls, empty otherwise - the same notation every find_* command
 * prints, so a failure pastes straight into hover or find_reference.
 *
 * messageLines is the failure message split into lines (empty for a pass), kept
 * as a list rather than one string so a handler can indent it without having to
 * split it back apart. origin names where the exception actually came from when
 * that is somewhere other than the test's own line; empty when the two coincide,
 * which is the normal case for a plain failed assertion.
 */
public record TestOutcome(Status status, String name, String location, List<String> messageLines, String origin) {

	public enum Status {
		PASSED, FAILED, SKIPPED
	}

	public TestOutcome {
		if (status == null)
			throw new IllegalArgumentException("status must not be null");

		if (name == null || name.isEmpty())
			throw new IllegalArgumentException("name must not be empty");

		if (location == null)
			throw new IllegalArgumentException("location must not be null - use \"\" when unknown");

		if (origin == null)
			throw new IllegalArgumentException("origin must not be null - use \"\" when it adds nothing");

		if (messageLines == null)
			throw new IllegalArgumentException("messageLines must not be null - use List.of()");

		messageLines = List.copyOf(messageLines);
	}

	public static TestOutcome passed(final String name) {
		return new TestOutcome(Status.PASSED, name, "", List.of(), "");
	}

	public static TestOutcome skipped(final String name, final String reason) {
		return new TestOutcome(Status.SKIPPED, name, "", List.of(reason), "");
	}

}
