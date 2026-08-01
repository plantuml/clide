package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.Truc;

/**
 * Every real usage of a symbol across the whole project - replaces the former
 * goto_references, removed once this made it redundant (see CLAUDE.md); same
 * request, same underlying pipeline (see PositionCommandSupport.goToAndFormat()).
 * Takes a leading <what> parameter for naming symmetry with
 * FindDeclarationCommand, but - unlike find_declaration - <what> does not
 * select between two different LSP requests here: textDocument/references is
 * sent either way, since jdtls answers "who uses this" the same way
 * regardless of whether the symbol being searched is a method or a type. Its
 * own literal value is still checked (typo protection, consistent with
 * find_declaration), but - deliberately, to avoid an extra jdtls round trip
 * just to find out - it is not cross-checked against the actual kind of
 * symbol found at <symbol>'s position.
 */
public class FindReferenceCommand extends Command {

	@Keyword("find_reference")
	@Help("Finds every real usage of a symbol across the whole project - <what> is method or type, <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				find_reference - find every real usage of a symbol

			SYNOPSIS
				find_reference <what> <symbol>

			DESCRIPTION
				Finds every real usage of a symbol across the whole
				project, excluding its own declaration - is this actually
				used anywhere, and where. <symbol> is given as
				<file path>:<line>:<name>, name located as a whole word on
				line of file path. <what> states whether <symbol> is a
				method or a type, for consistency with find_declaration/
				find_implementation; it does not change the result.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected. <symbol> must parse as <file path>:<line>:<name>
				- the file must exist under the project root, line must be
				within it, and name must appear on it as a whole word.

			SEE ALSO
				find_declaration(1), find_implementation(1), find_symbol(1)
			""")
	public FindReferenceCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String what = params[0];
		if (what.equals("method") == false && what.equals("type") == false)
			return CommandResult.error("Invalid <what> '" + what + "' - expected \"method\" or \"type\"");

		return PositionCommandSupport.goToAndFormat(context, "find_reference", "textDocument/references", params[1],
				Truc.of("includeDeclaration", false));
	}

}
