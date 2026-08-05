package clide.command.navigate;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.Monomorphic;

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
 * symbol found at <position>.
 */
public class FindReferenceCommand extends Command {

	@Keyword("find_reference")
	@Help("Finds every real usage of a symbol across the whole project - <what> is method or type, <position> as <file path>:<line>:<column>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_reference - find every real usage of a symbol

			SYNOPSIS
				find_reference <what> <position>

			DESCRIPTION
				Finds every real usage of a symbol across the whole
				project, excluding its own declaration - is this actually
				used anywhere, and where. <position> is given as
				<file path>:<line>:<column>:<name>, name starting exactly
				at column of that line. <what> states whether <position> names a
				method or a type, for consistency with find_declaration/
				find_implementation; it does not change the result.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected. <position> must parse as
				<file path>:<line>:<column>:<name> - the file must exist
				under the project root, line must be within it, and name
				must start exactly at column of that line as a whole word.
				Line and column both count from 1. A name present on the
				line but at another column is refused too, naming the
				columns it does start at: an edit that shifted the line is
				caught rather than silently answered.

			SEE ALSO
				find_declaration(1), find_implementation(1), find_symbol(1)
			""")
	public FindReferenceCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("what", params[0], "method", "type");
		if (rejected != null)
			return rejected;

		return PositionCommandSupport.goTo(context, "find_reference", "textDocument/references", params[1],
				Monomorphic.mapBuilder().putBoolean("includeDeclaration", false).build());
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_reference", result, printMode);
	}

}
