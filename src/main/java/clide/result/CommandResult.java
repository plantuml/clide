package clide.result;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of one command execution: a small envelope that is the same for
 * every command, plus the payload that is not (see CommandPayload).
 *
 * It used to be a status and a block of text, which meant the text *was* the
 * result: nothing could count it, cap it, branch on why it failed, or render it
 * differently for a human and for a machine without parsing clide's own output
 * back. The rule now is the other way round - the payload is the truth, the text
 * is one view of it, produced by Command.render() and by nothing else.
 *
 * The envelope, and only the envelope, is common to every command:
 * <ul>
 * <li><b>status</b> - answered, or refused. Binary; see CommandStatus.</li>
 * <li><b>code</b> - why it refused. NONE exactly when status is OK, so a caller
 * never has to check both.</li>
 * <li><b>message</b> - one line, for a person. Required on an error (an error
 * that says nothing is not one), and unused on success, where the payload
 * carries everything and render() writes the words.</li>
 * <li><b>hint</b> - the next thing to actually do ("run rebuild first"), when
 * there is one. Empty is normal; a hint invented to fill the field is worse than
 * no hint.</li>
 * <li><b>warnings</b> - may be non-empty on an OK result. See CommandStatus.</li>
 * <li><b>payload</b> - never null; CommandPayload.NOTHING when there is nothing
 * to report.</li>
 * </ul>
 *
 * Note what is deliberately NOT in the envelope: totalCount, returnedCount and
 * truncated. Those belong to a Listing inside the payloads that have a list -
 * see Listing's class doc for why hoisting them here would make hover and
 * open_transaction answer a question they have no answer to.
 *
 * Immutable; the with* methods return a copy.
 */
public record CommandResult(CommandStatus status, ErrorCode code, String message, String hint,
		List<Warning> warnings, CommandPayload payload) {

	public CommandResult {
		if (status == null)
			throw new IllegalArgumentException("status must not be null");

		if (code == null)
			throw new IllegalArgumentException("code must not be null - use ErrorCode.NONE");

		if (message == null)
			throw new IllegalArgumentException("message must not be null - use \"\"");

		if (hint == null)
			throw new IllegalArgumentException("hint must not be null - use \"\"");

		if (warnings == null)
			throw new IllegalArgumentException("warnings must not be null - use List.of()");

		if (payload == null)
			throw new IllegalArgumentException("payload must not be null - use CommandPayload.NOTHING");

		if (status == CommandStatus.OK && code != ErrorCode.NONE)
			throw new IllegalArgumentException("an OK result must carry ErrorCode.NONE, not " + code);

		if (status == CommandStatus.ERROR && code == ErrorCode.NONE)
			throw new IllegalArgumentException("an ERROR result must name why - ErrorCode.NONE is not a reason");

		if (status == CommandStatus.ERROR && message.isEmpty())
			throw new IllegalArgumentException("an ERROR result must say something - empty message for " + code);

		warnings = List.copyOf(warnings);
	}

	// ------------------------------------------------------------------
	// Success
	// ------------------------------------------------------------------

	public static CommandResult ok(final CommandPayload payload) {
		return new CommandResult(CommandStatus.OK, ErrorCode.NONE, "", "", List.of(), payload);
	}

	/** An OK result with nothing at all to report - exit, quit, terminate. */
	public static CommandResult empty() {
		return ok(CommandPayload.NOTHING);
	}

	// ------------------------------------------------------------------
	// Failure
	// ------------------------------------------------------------------

	public static CommandResult error(final ErrorCode code, final String message) {
		return new CommandResult(CommandStatus.ERROR, code, message, "", List.of(), CommandPayload.NOTHING);
	}

	public static CommandResult error(final ErrorCode code, final String message, final String hint) {
		return new CommandResult(CommandStatus.ERROR, code, message, hint, List.of(), CommandPayload.NOTHING);
	}

	/**
	 * An error that still has something to show - run_tests reporting which tests
	 * failed, typically. The payload is rendered after the error header, exactly
	 * as it would have been on success.
	 */
	public static CommandResult error(final ErrorCode code, final String message, final String hint,
			final CommandPayload payload) {
		return new CommandResult(CommandStatus.ERROR, code, message, hint, List.of(), payload);
	}

	// ------------------------------------------------------------------
	// Deriving
	// ------------------------------------------------------------------

	public CommandResult withHint(final String newHint) {
		return new CommandResult(status, code, message, newHint, warnings, payload);
	}

	public CommandResult withWarnings(final List<Warning> added) {
		if (added.isEmpty())
			return this;

		final List<Warning> merged = new ArrayList<>(warnings);
		merged.addAll(added);
		return new CommandResult(status, code, message, hint, merged, payload);
	}

	public CommandResult withWarning(final Warning added) {
		return withWarnings(List.of(added));
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	public boolean isError() {
		return status == CommandStatus.ERROR;
	}

	public boolean hasHint() {
		return hint.isEmpty() == false;
	}

}
