package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
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
