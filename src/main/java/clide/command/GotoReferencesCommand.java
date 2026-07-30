package clide.command;

import java.util.Map;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.annotation.ParamType;

/**
 * textDocument/references: every real usage of a symbol across the whole
 * project - the inverse of goto_implementation (which goes from an abstract
 * method/interface to its concrete overrides; this goes from any symbol to
 * everywhere it's actually used). includeDeclaration is false: the declaration
 * itself is already known (it's this command's own input), so only real usages
 * are wanted - with includeDeclaration true, a never-called method would still
 * report one "reference" (its own declaration), defeating the point of asking
 * "is this actually called anywhere".
 */
public class GotoReferencesCommand extends GotoPositionCommand {

	@Keyword("goto_references")
	@Help("Finds every real usage of a symbol across the whole project (not its own declaration), at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param(type = ParamType.SINGLE_LINE, description = "File path")
	@Param(type = ParamType.SINGLE_LINE, description = "Line")
	@Param(type = ParamType.SINGLE_LINE, description = "Symbol")
	public GotoReferencesCommand() {

	}

	@Override
	protected String lspMethod() {
		return "textDocument/references";
	}

	@Override
	protected String commandName() {
		return "goto_references";
	}

	@Override
	protected Map<String, Object> context() {
		return Map.of("includeDeclaration", false);
	}

}
