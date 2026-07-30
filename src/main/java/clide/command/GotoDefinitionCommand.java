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
			This is a long explanation.
			On several lines
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
