package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
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
	@Manual("""
			NAME
				help_ai - list every clide command, unformatted

			SYNOPSIS
				help_ai

			DESCRIPTION
				Prints the same information as help - every command's keyword,
				parameters and one-line description - but as one line per
				command, "keyword <param> ... - description", with no title,
				no borders, no wrapping, no separator lines. help's ASCII
				table trades a few extra bytes for human readability; help_ai
				trades that back for something an AI client (e.g. Claude) can
				parse without stripping decoration first.

			SEE ALSO
				help(1), man(1)
			""")
	public HelpAiCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final StringBuilder text = new StringBuilder();
		for (final Command command : context.getAllCommands()) {
			text.append(command.getKeyword());
			for (final String paramDescription : command.getDescriptionParam())
				text.append(" <").append(paramDescription).append('>');
			text.append(" - ").append(command.getHelp()).append('\n');
		}

		return CommandResult.ok(text.toString().strip());
	}

}
