package clide.lua;

import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;

/**
 * The one shape a failure takes on its way into a script: the same
 * "?ERROR &lt;CODE&gt;: &lt;message&gt;" line, and the same "hint:" line under
 * it, that a client reading text would have got - see ResultEnvelope.
 *
 * Written once here rather than at each raising site, for the reason
 * ResultEnvelope exists at all: a second way to spell a failure is a second
 * thing for a script to have to recognize. A script that branches on a code
 * branches on the same codes either façade reports, and one that only prints the
 * error prints a line a reader has already seen elsewhere.
 */
final class LuaErrors {

	private LuaErrors() {
	}

	/** The error a refused command becomes. result must be an ERROR. */
	static String text(final CommandResult result) {
		return text(result.code(), result.message(), result.hint());
	}

	static String text(final ErrorCode code, final String message, final String hint) {
		final StringBuilder out = new StringBuilder(ResultEnvelope.ERROR_PREFIX);
		out.append(code).append(": ").append(message);
		if (hint != null && hint.isEmpty() == false)
			out.append('\n').append(ResultEnvelope.HINT_PREFIX).append(hint);

		return out.toString();
	}

}
