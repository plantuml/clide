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
 * Harmonized front end over goto_definition/goto_type_definition: same
 * <symbol> parameter and the same underlying pipeline (see
 * GotoPositionCommand.goToAndFormat()), but the LSP method to send -
 * textDocument/definition or textDocument/typeDefinition - is picked at
 * runtime from a new leading <what> parameter instead of being fixed per
 * command class. goto_definition and goto_type_definition are unchanged and
 * keep working exactly as before; this is purely an additional, more
 * consistently-named entry point (see CLAUDE.md).
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
				own declaration - same request as goto_definition), "type"
				sends textDocument/typeDefinition (where the symbol's
				declared type - its class or interface - is defined, not
				the symbol's own declaration - same request as
				goto_type_definition). A harmonized, more consistently
				named front end over those two commands, which are
				unaffected and keep working exactly as before.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected before any jdtls request is sent. <symbol>
				must parse as <file path>:<line>:<name> - the file must
				exist under the project root, line must be within it, and
				name must appear on it as a whole word. The daemon checks
				all of this before find_declaration ever runs, and reports
				whichever check fails first.

			SEE ALSO
				find_reference(1), goto_definition(1),
				goto_type_definition(1), goto_implementation(1),
				find_symbol(1)
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

		return GotoPositionCommand.goToAndFormat(context, "find_declaration", lspMethod, params[1], null);
	}

}
