package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.result.CommandPayload;
import clide.result.CommandResult;
import clide.result.ErrorCode;

public class ManualCommand extends Command {

	@Keyword("man")
	@Help("please write help of man")
	@Param(type = ParamType.SINGLE_LINE, description = "Keyword")
	@Manual("""
			NAME
				man - display the manual page for a clide command

			SYNOPSIS
				man <keyword>

			DESCRIPTION
				Prints the manual page written for the command named <keyword>.
				Where help gives one line per command - just enough to remind
				you a command exists and what its parameters are - man is the
				long-form page for a single command: what it actually does,
				how it behaves at its edges, and why it works the way it
				does. Run help first to find the keyword you want, then man
				it.

				Not every command has a manual page written for it yet; one
				that doesn't returns an empty result, not an error. man itself
				always has one - this page is it, and it is what every other
				command's page is written to match.

			ERRORS
				man <keyword> fails only when <keyword> does not match any
				command registered with clide.

			SEE ALSO
				help(1)
			""")
	public ManualCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Command command = context.getCommand(params[0]);
		if (command == null)
			return CommandResult.error(ErrorCode.UNKNOWN_KEYWORD, "Invalid keyword '" + params[0] + "'");

		return CommandResult.ok(new CommandPayload.Text(command.getManual()));
	}

}
