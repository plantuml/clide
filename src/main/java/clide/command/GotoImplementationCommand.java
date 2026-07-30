package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;

/**
 * textDocument/implementation: which concrete classes/methods actually
 * implement or override a symbol (typically an interface method or an abstract
 * method) - the polymorphism question a plain grep can't answer.
 */
public class GotoImplementationCommand extends GotoPositionCommand {

	@Keyword("goto_implementation")
	@Help("Finds classes or methods that implement/override a symbol (e.g. an interface or abstract method) - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				goto_implementation - find what implements or overrides a symbol

			SYNOPSIS
				goto_implementation <symbol>

			DESCRIPTION
				Sends textDocument/implementation to jdtls for <symbol> and
				reports which concrete classes or methods actually
				implement or override it - typically an interface method or
				an abstract method: the polymorphism question a plain grep
				can't answer. <symbol> is given as <file path>:<line>:<name>,
				name located as a whole word on line of file path. Shares
				its parameter resolution and result formatting with
				goto_definition, goto_type_definition and goto_references;
				only the LSP method it sends differs.

			ERRORS
				<symbol> must parse as <file path>:<line>:<name> - the file
				must exist under the project root, line must be within it,
				and name must appear on it as a whole word. The daemon
				checks all of this before goto_implementation ever runs,
				and reports whichever check fails first.

			SEE ALSO
				goto_definition(1), goto_type_definition(1),
				goto_references(1), find_symbol(1)
			""")
	public GotoImplementationCommand() {

	}

	@Override
	protected String lspMethod() {
		return "textDocument/implementation";
	}

	@Override
	protected String commandName() {
		return "goto_implementation";
	}

}
