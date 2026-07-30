package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

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
	@Help("Finds where a symbol is really declared - <what> is method or type, <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				find_declaration - find where a symbol is really declared

			SYNOPSIS
				find_declaration <what> <symbol>

			DESCRIPTION
				Finds where a symbol is really declared - the file and line
				it's actually defined at, not just a place it's used or
				referenced. <symbol> is given as <file path>:<line>:<name>,
				name located as a whole word on line of file path; the
				result may live in a completely different file or class
				(e.g. an interface method implemented elsewhere). <what>
				says what kind of declaration is wanted: "method" for the
				symbol's own declaration, "type" for the class or interface
				of the symbol's declared type (not the symbol's own
				declaration).

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected. <symbol> must parse as <file path>:<line>:<name>
				- the file must exist under the project root, line must be
				within it, and name must appear on it as a whole word.

			SEE ALSO
				find_reference(1), find_implementation(1), find_symbol(1)
			""")
	public FindDeclarationCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String what = params[0];
		final String lspMethod;
		if (what.equals("method"))
			lspMethod = "textDocument/definition";
		else if (what.equals("type"))
			lspMethod = "textDocument/typeDefinition";
		else
			return CommandResult.error("Invalid <what> '" + what + "' - expected \"method\" or \"type\"");

		return PositionCommandSupport.goToAndFormat(context, "find_declaration", lspMethod, params[1], null);
	}

}
