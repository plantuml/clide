package clide.model;

import java.util.List;

/**
 * What happened to one test. location is "path:line" - no column: a stack frame
 * carries none, so it is not a full <position>. Filled in when clide could place the
 * test through jdtls, empty otherwise - the same notation every find_* command
 * prints, so a failure pastes straight into hover or find_reference.
 *
 * messageLines is the failure message split into lines (empty for a pass), kept
 * as a list rather than one string so a handler can indent it without having to
 * split it back apart. origin names where the exception actually came from when
 * that is somewhere other than the test's own line; empty when the two coincide,
 * which is the normal case for a plain failed assertion.
 *
 * measure is what the test cost - see TestMeasure; TestMeasure.UNKNOWN for a
 * test that did not run (skipped) or that a runner without measures reported.
 */
public record TestOutcome(Status status, String name, String location, List<String> messageLines, String origin,
		TestMeasure measure) {

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

		if (measure == null)
			throw new IllegalArgumentException("measure must not be null - use TestMeasure.UNKNOWN");

		messageLines = List.copyOf(messageLines);
	}

	/** An outcome nobody measured - what a test that did not run is, and what older call sites build. */
	public TestOutcome(final Status status, final String name, final String location, final List<String> messageLines,
			final String origin) {
		this(status, name, location, messageLines, origin, TestMeasure.UNKNOWN);
	}

	public static TestOutcome passed(final String name) {
		return passed(name, TestMeasure.UNKNOWN);
	}

	public static TestOutcome passed(final String name, final TestMeasure measure) {
		return new TestOutcome(Status.PASSED, name, "", List.of(), "", measure);
	}

	public static TestOutcome skipped(final String name, final String reason) {
		return new TestOutcome(Status.SKIPPED, name, "", List.of(reason), "", TestMeasure.UNKNOWN);
	}

}
