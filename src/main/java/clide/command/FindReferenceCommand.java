package clide.command;

import java.util.Map;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

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
				Sends textDocument/references to jdtls for <symbol> -
				<file path>:<line>:<name>, name located as a whole word on
				line of file path - and reports every real usage of it
				across the whole project, excluding its own declaration.
				<what> does not change which LSP request is sent (there is
				only one, regardless of whether the symbol at <symbol> is
				a method or a type): it exists for naming symmetry with
				find_declaration, and its own literal value is checked
				("method" or "type", nothing else), but it is not verified
				against the actual kind of symbol found at that position -
				doing so would cost an extra jdtls round trip for a check
				textDocument/references does not itself need. Replaces the
				former goto_references command, which sent this exact same
				request but without a <what> parameter.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected before any jdtls request is sent. <symbol>
				must parse as <file path>:<line>:<name> - the file must
				exist under the project root, line must be within it, and
				name must appear on it as a whole word. The daemon checks
				all of this before find_reference ever runs, and reports
				whichever check fails first.

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
				Map.of("includeDeclaration", false));
	}

}
