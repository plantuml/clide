package clide.command;

import java.util.List;

import clide.core.Position;
import clide.core.PositionException;
import clide.result.CommandResult;
import clide.result.ErrorCode;
import clide.result.Warning;
import clide.result.WarningCode;

/**
 * The two things nearly every command has to do with a &lt;position&gt;, in one
 * place rather than copied into each of them: turn a parse failure into a
 * CommandResult carrying the right ErrorCode, and notice when the name was
 * ambiguous on its line.
 */
final class CommandResults {

	private CommandResults() {
	}

	/**
	 * Parses positionText, or returns null and leaves the failure in failure[0] -
	 * an awkward shape that exists so a caller can write the happy path straight
	 * down without a try/catch around it, and without this helper having to know
	 * which command is asking.
	 */
	static CommandResult positionFailure(final RuntimeException e) {
		return CommandResult.error(PositionException.codeOf(e), e.getMessage());
	}

	/**
	 * The warning to attach when position's name occurs more than once on its
	 * line, empty otherwise - see WarningCode.AMBIGUOUS_NAME_ON_LINE for why this
	 * is a warning and not a refusal.
	 *
	 * The message names every column, 1-based here rather than 0-based: a column
	 * a person reads off their editor is 1-based, and this text exists to be read.
	 * clide's own resolution stays 0-based internally and is not affected.
	 */
	static List<Warning> ambiguityWarnings(final Position position) {
		if (position.isAmbiguousOnLine() == false)
			return List.of();

		final StringBuilder columns = new StringBuilder();
		for (final int column : position.columnsOnLine()) {
			if (columns.length() > 0)
				columns.append(", ");

			columns.append(column + 1);
		}

		return List.of(Warning.of(WarningCode.AMBIGUOUS_NAME_ON_LINE,
				"'" + position.name() + "' appears " + position.columnsOnLine().size() + " times on line "
						+ position.line() + " (columns " + columns + ") - answered about the first one"));
	}

	/**
	 * "method"/"type" and friends: the check every command with a fixed-vocabulary
	 * parameter runs, worded the same way each time. Returns null when value is
	 * one of allowed.
	 */
	static CommandResult rejectUnlessOneOf(final String label, final String value, final String... allowed) {
		for (final String candidate : allowed)
			if (value.equals(candidate))
				return null;

		final StringBuilder expected = new StringBuilder();
		for (final String candidate : allowed) {
			if (expected.length() > 0)
				expected.append(" or ");

			expected.append('"').append(candidate).append('"');
		}

		return CommandResult.error(ErrorCode.INVALID_ENUM_VALUE,
				"Invalid <" + label + "> '" + value + "' - expected " + expected);
	}

}
