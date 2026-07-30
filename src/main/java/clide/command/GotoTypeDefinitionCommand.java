package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;

/**
 * textDocument/typeDefinition: where the declared type of a symbol is defined
 * (its class/interface), not the symbol's own declaration - and not its runtime
 * type either, LSP only knows the statically declared one.
 */
public class GotoTypeDefinitionCommand extends GotoPositionCommand {

	@Keyword("goto_type_definition")
	@Help("Finds where the declared type of a symbol is defined (its class or interface) - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				goto_type_definition - find where a symbol's declared type is defined

			SYNOPSIS
				goto_type_definition <symbol>

			DESCRIPTION
				Sends textDocument/typeDefinition to jdtls for <symbol> and
				reports where its declared type - its class or interface -
				is defined: not the symbol's own declaration, and not its
				runtime type either, LSP only knows the statically declared
				one. <symbol> is given as <file path>:<line>:<name>, name
				located as a whole word on line of file path. Shares its
				parameter resolution and result formatting with
				goto_definition, goto_implementation and goto_references;
				only the LSP method it sends differs.

			ERRORS
				<symbol> must parse as <file path>:<line>:<name> - the file
				must exist under the project root, line must be within it,
				and name must appear on it as a whole word. The daemon
				checks all of this before goto_type_definition ever runs,
				and reports whichever check fails first.

			SEE ALSO
				goto_definition(1), goto_implementation(1),
				goto_references(1), find_symbol(1)
			""")
	public GotoTypeDefinitionCommand() {

	}

	@Override
	protected String lspMethod() {
		return "textDocument/typeDefinition";
	}

	@Override
	protected String commandName() {
		return "goto_type_definition";
	}

}
