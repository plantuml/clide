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
				Finds the real declaration of the symbol used or referenced
				at <symbol> - <file path>:<line>:<name>, name located as a
				whole word on line of file path - which may live in a
				completely different class (e.g. an interface method
				implemented elsewhere). <what> picks which LSP request is
				sent: "method" sends textDocument/definition (the symbol's
				own declaration), "type" sends textDocument/typeDefinition
				(where the symbol's declared type - its class or interface -
				is defined, not the symbol's own declaration). Replaces the
				former goto_definition/goto_type_definition commands, which
				sent these same two requests but as two separate commands
				with no <what> to tell them apart.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected before any jdtls request is sent. <symbol>
				must parse as <file path>:<line>:<name> - the file must
				exist under the project root, line must be within it, and
				name must appear on it as a whole word. The daemon checks
				all of this before find_declaration ever runs, and reports
				whichever check fails first.

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
