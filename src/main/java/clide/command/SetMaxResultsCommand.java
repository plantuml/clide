package clide.command;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Sets how many entries the commands that answer with a list return - see
 * Listing, ClideContext.getMaxResults().
 *
 * A session setting rather than a parameter on each of those commands, because
 * the line protocol has no optional parameter: arity is fixed and known up front
 * from help (see CLAUDE.md), so a &lt;max results&gt; on find_reference would be
 * mandatory on every single call, and every batch written before it existed
 * would break. A cap is a preference of the session anyway, not part of the
 * question being asked.
 *
 * It reports the previous value alongside the new one for the same protocol
 * reason: with a fixed arity there is no argument-less form to read the current
 * setting with, so setting it *is* how you find out what it was.
 */
public class SetMaxResultsCommand extends Command {

	@Keyword("set_max_results")
	@Help("Sets how many entries a listing command returns at most for this session - <count> is 0 or more.")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Count")
	@Manual("""
			NAME
				set_max_results - cap how many entries a listing command returns

			SYNOPSIS
				set_max_results <count>

			DESCRIPTION
				Sets the cap the commands that answer with a list apply to
				their results for the rest of this session: find_declaration,
				find_reference, find_implementation, find_symbol,
				list_members, search_regex, print_diagnostics, rebuild and
				run_tests. Prints the previous value and the new one, which
				is also the only way to read the current setting back - the
				protocol's fixed arity leaves no room for an argument-less
				form.

				The cap never changes what clide counts. A capped result
				still reports the real total and says it was truncated, e.g.
				"50 location(s) shown out of 312, truncated"; a result of
				exactly <count> entries with nothing left over is NOT
				reported as truncated.

				The setting belongs to the connection, not to the daemon: it
				goes back to 100 at the start of every new session, so a cap
				set here is never inherited by a later one that has no way of
				knowing it was set.

			ERRORS
				<count> must parse as an integer of 0 or more; anything else
				is refused naming the parameter, and never quietly repaired.
				0 is honoured literally - zero entries returned, totals still
				exact and truncation still reported - rather than clamped up
				to 1. Values above 10000 are refused naming that ceiling,
				rather than silently clamped down to it.

			SEE ALSO
				find_reference(1), search_regex(1), help(1)
			""")
	public SetMaxResultsCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		// Already known to parse as a non-negative integer - ParamType
		// NON_NEGATIVE_INTEGER checked that before this ran (see
		// ClideDaemon.validate()). Only the ceiling is left, and it is clide's own
		// rule rather than a property of the text, so it is checked here.
		final int requested = Integer.parseInt(params[0]);
		final int previous = context.getMaxResults();
		try {
			context.setMaxResults(requested);
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, e.getMessage(),
					"the cap stays at " + previous);
		}

		return CommandResult.ok(
				new CommandPayload.Setting("max_results", String.valueOf(previous), String.valueOf(requested)));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.Setting setting) {
			return "set_max_results: " + setting.name() + " " + setting.previousValue() + " -> " + setting.newValue();
		}

		return "";
	}

}
