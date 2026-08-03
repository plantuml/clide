package clide.command;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.util.TextTable;

/**
 * Lists every registered command with its parameters and description, in
 * whichever shape this connection's print mode calls for: an ASCII table for a
 * human ("clide --human"), one bare line per command for anyone else. Both
 * renderings say exactly the same thing and are built from the same
 * &#64;Keyword/&#64;Param/&#64;Help annotations - only the decoration differs, so
 * there is nothing for an AI client to strip and nothing for a person to
 * squint at.
 */
public class HelpCommand extends Command {

	@Keyword("help")
	@Help("Lists every available command with its parameters - one line each, or an ASCII table under --human.")
	@Manual("""
			NAME
				help - list every clide command

			SYNOPSIS
				help

			DESCRIPTION
				Prints every command registered with this clide daemon:
				keyword, parameters, one-line description - read straight off
				each command's own @Keyword, @Param and @Help. Takes no
				parameters and lists everything at once; run man <keyword>
				afterwards to read one command's full page.

				How that list is rendered follows the session's print mode
				(see PrintMode, CLAUDE.md), and nothing else. In the default
				AI mode: one line per command, "keyword <param> ... -
				description", with no title, no borders, no wrapping and no
				separator lines - nothing a client has to strip before
				parsing it. Under "clide --human": the same content laid out
				as a fixed-width ASCII table, which costs a few extra bytes
				and reads far better on a terminal.

			SEE ALSO
				man(1)
			""")
	public HelpCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		if (context.getPrintMode() == PrintMode.HUMAN)
			return CommandResult.ok(renderTable(context));

		return CommandResult.ok(renderOneLinePerCommand(context));
	}

	/** HUMAN rendering: a fixed-width ASCII table, one row per command. */
	private String renderTable(final ClideContext context) {
		final TextTable table = new TextTable(80, "Keyword", "Parameters", "Description");

		for (final Command command : context.getAllCommands()) {
			table.addRow(command.getKeyword(), formatParams(command), command.getHelp());
			table.addEmptyRow();
		}

		return table.render().strip();
	}

	/** AI rendering: "keyword &lt;param&gt; ... - description", one line per command. */
	private String renderOneLinePerCommand(final ClideContext context) {
		final StringBuilder text = new StringBuilder();

		for (final Command command : context.getAllCommands()) {
			text.append(command.getKeyword());
			for (final String paramDescription : command.getDescriptionParam())
				text.append(" <").append(paramDescription).append('>');
			text.append(" - ").append(command.getHelp()).append('\n');
		}

		return text.toString().strip();
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
