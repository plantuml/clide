package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;

/**
 * textDocument/implementation: which concrete classes/methods actually
 * implement or override a symbol (typically an interface method or an
 * abstract method) - the polymorphism question a plain grep can't answer.
 */
public class GotoImplementationCommand extends GotoPositionCommand {

	@Keyword("goto_implementation")
	@Help("Finds classes or methods that implement/override a symbol (e.g. an interface or abstract method), at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param("File path")
	@Param("Line")
	@Param("Symbol")
	public GotoImplementationCommand() {
		// Constructeur
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
