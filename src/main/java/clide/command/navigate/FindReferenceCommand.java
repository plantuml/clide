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
	@Help("Finds every real usage of a symbol across the whole project - <what> is method or type, <position> as <file-content-md5>:<file path>:<line>:<column>:<name>.")
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
				<file-content-md5>:<file path>:<line>:<column>:<name>,
				name starting exactly at column of that line. <what> states whether <position> names a
				method or a type, for consistency with find_declaration/
				find_implementation; it does not change the result.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected. <position> must parse as
				<file-content-md5>:<file path>:<line>:<column>:<name> -
				the file must exist under the project root, line must be within it, and name
				must start exactly at column of that line as a whole word.
				Line and column both count from 1. A name present on the
				line but at another column is refused too, naming the
				columns it does start at: an edit that shifted the line is
				caught rather than silently answered.

				When <position> carries a <file-content-md5>, that
				signature must still be the file's own: a file edited
				since the position was produced is refused
				(FILE_MODIFIED) rather than answered about. The md5 is
				optional on input - a <position> written without it
				means "against the file currently on disk" - but clide
				always prints one, so a result pasted straight back in
				carries the check with it.

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
