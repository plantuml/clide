package clide.command;

import java.util.Set;
import java.util.TreeSet;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.util.TextTable;

/** Lists every registered command with its parameters and description. */
public class HelpCommand extends Command {

	@Keyword("help")
	@Help("Lists every available command with its parameters.")
	public HelpCommand() {
		// Constructeur
	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final TextTable table = new TextTable(80, "Keyword", "Parameters", "Description");

		final Set<Command> commands = new TreeSet<>(context.getCommands());

		for (final Command command : commands) {
			table.addRow(command.getKeyword(), formatParams(command), command.getHelp());
			table.addEmptyRow();
		}

		return CommandResult.ok(table.render().strip());
	}

	private String formatParams(final Command command) {
		final StringBuilder params = new StringBuilder();
		for (final String paramDescription : command.getDescriptionParam()) {
			if (params.length() > 0)
				params.append('\n');
			params.append('<').append(paramDescription).append('>');
		}

		return params.toString();
	}

}
