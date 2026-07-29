package clide.command;

import java.util.Set;
import java.util.TreeSet;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/**
 * Lists every registered command, one line per command, with zero decorative
 * overhead: no title, no borders, no wrapping, no separator lines - just
 * "keyword &lt;param&gt; ... - description", sorted alphabetically by keyword.
 * Meant for an AI client (e.g. Claude) to parse, as opposed to HelpCommand's
 * ASCII table, which trades a few extra bytes for human readability.
 */
public class HelpAiCommand extends Command {

	@Keyword("help_ai")
	@Help("Lists every available command, one line per command, with no decorative formatting - meant for an AI client.")
	public HelpAiCommand() {
		// Constructeur
	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Set<Command> commands = new TreeSet<>(context.getCommands());

		final StringBuilder text = new StringBuilder();
		for (final Command command : commands) {
			text.append(command.getKeyword());
			for (final String paramDescription : command.getDescriptionParam())
				text.append(" <").append(paramDescription).append('>');
			text.append(" - ").append(command.getHelp()).append('\n');
		}

		return CommandResult.ok(text.toString().strip());
	}

}
