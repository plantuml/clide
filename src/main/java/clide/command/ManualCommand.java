package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

public class ManualCommand extends Command {

	@Keyword("man")
	@Help("please write help of man")
	@Param(type = ParamType.SINGLE_LINE, description = "Keyword")
	@Manual("""
			This is a long explanation.
			On several lines.

			This should explain 'man' in details.
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
			return CommandResult.error("Invalid keyword '" + params[0] + "'");

		return CommandResult.ok(command.getManual());
	}

}
