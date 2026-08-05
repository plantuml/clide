package clide.command;

import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.PositionException;

/**
 * The two checks nearly every command runs on its own parameters, in one place
 * rather than copied into each of them: turning a &lt;position&gt; parse
 * failure into a CommandResult carrying the right ErrorCode (and hint), and
 * refusing a fixed-vocabulary parameter that got something outside its
 * vocabulary.
 *
 * It used to hold a third one, ambiguityWarnings(): the &lt;position&gt;
 * notation carried no column, so clide resolved the first whole-word occurrence
 * on the line and warned when there were several. The notation now names the
 * column (see Position, SYMBOLS.md), so there is no first-occurrence choice
 * left to make and nothing to warn about - the token either designates exactly
 * one spot or is refused.
 */
public final class CommandResults {

	private CommandResults() {
	}

	/**
	 * The refusal to answer with, for a &lt;position&gt; PositionParser.parse() would
	 * not take - the code says which of the ways it failed, and the hint (usually
	 * empty) carries whatever Position computed that the caller could not.
	 */
	public static CommandResult positionFailure(final RuntimeException e) {
		return CommandResult.error(PositionException.codeOf(e), e.getMessage(), PositionException.hintOf(e));
	}

	/**
	 * "method"/"type" and friends: the check every command with a fixed-vocabulary
	 * parameter runs, worded the same way each time. Returns null when value is
	 * one of allowed.
	 */
	public static CommandResult rejectUnlessOneOf(final String label, final String value, final String... allowed) {
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
