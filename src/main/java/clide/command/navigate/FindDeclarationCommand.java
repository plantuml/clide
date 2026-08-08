package clide.command.navigate;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Where a symbol is really declared - <what> picks which LSP request goes
 * out (textDocument/definition for "method", textDocument/typeDefinition for
 * "type"), then reuses the same pipeline as every other position-based
 * command (see PositionCommandSupport.goToAndFormat()). Replaces the former
 * goto_definition/goto_type_definition, which sent exactly these same two
 * requests but as two separate commands with no <what> to tell them apart -
 * removed once this made them redundant (see CLAUDE.md).
 */
public class FindDeclarationCommand extends Command {

	@Keyword("find_declaration")
	@Help("Finds where a symbol is really declared - <what> is method or type, <position> as <file-content-md5>:<file path>:<line>:<column>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_declaration - find where a symbol is really declared

			SYNOPSIS
				find_declaration <what> <position>

			DESCRIPTION
				Finds where a symbol is really declared - the file and line
				it's actually defined at, not just a place it's used or
				referenced. <position> is given as
				<file-content-md5>:<file path>:<line>:<column>:<name>,
				name starting exactly at column of that line; the result may live in a completely
				different file or class (e.g. an interface method
				implemented elsewhere). <what> says what kind of
				declaration is wanted: "method" for the symbol's own
				declaration, "type" for the class or interface of the
				symbol's declared type (not the symbol's own declaration).

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected. <position> must parse as
				<file-content-md5>:<file path>:<line>:<column>:<name> -
				the file must exist under the project root, line must be within it, and name
				must start exactly at column of that line as a whole word.
				Line and column both count from 1. A name present on the
				line but at another column is refused too, naming the
				columns it does start at: an edit that shifted the line is
				caught rather than silently answered.

				When <position> carries a <file-content-md5>, that
				signature must still be the file's own: a file edited
				since the position was produced is refused
				(FILE_MODIFIED) rather than answered about. The md5 is
				optional on input - a <position> written without it
				means "against the file currently on disk" - but clide
				always prints one, so a result pasted straight back in
				carries the check with it.

			SEE ALSO
				find_reference(1), find_implementation(1), find_symbol(1)
			""")
	public FindDeclarationCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("what", params[0], "method", "type");
		if (rejected != null)
			return rejected;

		final String lspMethod = params[0].equals("method") ? "textDocument/definition"
				: "textDocument/typeDefinition";
		return PositionCommandSupport.goTo(context, "find_declaration", lspMethod, params[1], null);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_declaration", result, printMode);
	}

}
