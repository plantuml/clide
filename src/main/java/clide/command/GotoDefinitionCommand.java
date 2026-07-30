package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;

/**
 * textDocument/definition: where a symbol is really defined (its own
 * declaration), not just where it happens to be used.
 */
public class GotoDefinitionCommand extends GotoPositionCommand {

	@Keyword("goto_definition")
	@Help("Finds where a symbol is really defined (its declaration), not just used - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				goto_definition - find where a symbol is really defined

			SYNOPSIS
				goto_definition <symbol>

			DESCRIPTION
				Sends textDocument/definition to jdtls for <symbol> and
				reports where it's really defined - its own declaration -
				not just one of the places it happens to be used. <symbol>
				is given as <file path>:<line>:<name>, name located as a
				whole word on line of file path. Shares its parameter
				resolution and result formatting with goto_type_definition,
				goto_implementation and goto_references; only the LSP
				method it sends differs.

			ERRORS
				<symbol> must parse as <file path>:<line>:<name> - the file
				must exist under the project root, line must be within it,
				and name must appear on it as a whole word. The daemon
				checks all of this before goto_definition ever runs, and
				reports whichever check fails first.

			SEE ALSO
				goto_type_definition(1), goto_implementation(1),
				goto_references(1), find_symbol(1)
			""")
	public GotoDefinitionCommand() {

	}

	@Override
	protected String lspMethod() {
		return "textDocument/definition";
	}

	@Override
	protected String commandName() {
		return "goto_definition";
	}

}
