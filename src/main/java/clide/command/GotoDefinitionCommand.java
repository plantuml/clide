package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;

/**
 * textDocument/definition: where a symbol is really defined (its own
 * declaration), not just where it happens to be used.
 */
public class GotoDefinitionCommand extends GotoPositionCommand {

	@Keyword("goto_definition")
	@Help("Finds where a symbol is really defined (its declaration), not just used, at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param("File path")
	@Param("Line")
	@Param("Symbol")
	public GotoDefinitionCommand() {
		// Constructeur
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
