package clide.command;

import java.util.Map;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
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
	@Help("Finds every real usage of a symbol across the whole project (not its own declaration) - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				goto_references - find every real usage of a symbol

			SYNOPSIS
				goto_references <symbol>

			DESCRIPTION
				Sends textDocument/references to jdtls for <symbol> and
				reports every real usage of it across the whole project -
				the inverse of goto_implementation, which goes from an
				abstract method or interface to its concrete overrides;
				this goes from any symbol to everywhere it's actually used.
				The symbol's own declaration is excluded from the results:
				it's already known, being this command's own input, so a
				never-called method correctly reports zero references
				rather than one (its own declaration). <symbol> is given as
				<file path>:<line>:<name>, name located as a whole word on
				line of file path. Shares its parameter resolution and
				result formatting with goto_definition, goto_type_definition
				and goto_implementation; only the LSP method it sends (and
				this exclusion) differs.

			ERRORS
				<symbol> must parse as <file path>:<line>:<name> - the file
				must exist under the project root, line must be within it,
				and name must appear on it as a whole word. The daemon
				checks all of this before goto_references ever runs, and
				reports whichever check fails first.

			SEE ALSO
				goto_definition(1), goto_type_definition(1),
				goto_implementation(1), find_symbol(1)
			""")
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
