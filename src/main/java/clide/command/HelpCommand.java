package clide.command;

import java.util.ArrayList;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.CommandSummary;
import clide.core.ClideContext;
import clide.core.Command;
import clide.model.Listing;
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

	/**
	 * Collects what every registered command says about itself. Never capped: help
	 * listing only some of the commands would be a listing a client cannot trust,
	 * and the count is a couple of dozen by construction - so Listing.of() gets the
	 * full size as its own limit rather than this connection's max_results.
	 */
	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final List<CommandSummary> summaries = new ArrayList<>();
		for (final Command command : context.getAllCommands())
			summaries.add(new CommandSummary(command.getKeyword(), List.of(command.getDescriptionParam()),
					command.getHelp()));

		return CommandResult.ok(new CommandPayload.CommandList(Listing.of(summaries, summaries.size())));
	}

	/**
	 * The one command whose rendering genuinely differs by print mode - and the
	 * reason Command.render() is handed one at all. Both shapes say exactly the
	 * same thing, off the same payload: nothing for an AI client to strip, nothing
	 * for a person to squint at.
	 */
	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.CommandList listed) {
			if (printMode == PrintMode.HUMAN)
				return renderTable(listed.commands().items());

			return renderOneLinePerCommand(listed.commands().items());
		}

		return "";
	}

	/** HUMAN rendering: a fixed-width ASCII table, one row per command. */
	private String renderTable(final List<CommandSummary> commands) {
		final TextTable table = new TextTable(80, "Keyword", "Parameters", "Description");

		for (final CommandSummary command : commands) {
			table.addRow(command.keyword(), String.join("\n", bracketed(command)), command.help());
			table.addEmptyRow();
		}

		return table.render().strip();
	}

	/** AI rendering: "keyword &lt;param&gt; ... - description", one line per command. */
	private String renderOneLinePerCommand(final List<CommandSummary> commands) {
		final StringBuilder text = new StringBuilder();

		for (final CommandSummary command : commands) {
			if (text.length() > 0)
				text.append('\n');

			text.append(command.keyword());
			if (command.parameters().isEmpty() == false)
				text.append(' ').append(command.parametersDisplay());

			text.append(" - ").append(command.help());
		}

		return text.toString();
	}

	/** The parameter labels, each in its own angle brackets - one per table line. */
	private List<String> bracketed(final CommandSummary command) {
		final List<String> params = new ArrayList<>();
		for (final String parameter : command.parameters())
			params.add("<" + parameter + ">");

		return params;
	}

}
