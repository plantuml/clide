package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;

/**
 * textDocument/typeDefinition: where the declared type of a symbol is
 * defined (its class/interface), not the symbol's own declaration - and not
 * its runtime type either, LSP only knows the statically declared one.
 */
public class GotoTypeDefinitionCommand extends GotoPositionCommand {

	@Keyword("goto_type_definition")
	@Help("Finds where the declared type of a symbol is defined (its class or interface), at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param("File path")
	@Param("Line")
	@Param("Symbol")
	public GotoTypeDefinitionCommand() {
		// Constructeur
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
