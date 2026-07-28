package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/** Lists every registered command with its parameters and description. */
public class HelpCommand extends Command {

	@Keyword("help")
	@Help("Lists every available command with its parameters.")
	public HelpCommand() {
		// Constructeur
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final StringBuilder text = new StringBuilder("Available commands:\n");
		for (final Command command : context.getCommands()) {
			text.append(command.getKeyword());
			for (final String paramDescription : command.getDescriptionParam())
				text.append(" <").append(paramDescription).append('>');
			text.append(" - ").append(command.getHelp()).append('\n');
		}
		return CommandResult.ok(text.toString().strip());
	}

}
