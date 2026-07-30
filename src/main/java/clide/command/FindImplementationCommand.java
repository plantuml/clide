package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/**
 * Which concrete classes/methods actually implement or override a symbol -
 * replaces the former goto_implementation, removed once find_declaration/
 * find_reference set the naming pattern this follows (see CLAUDE.md); same
 * textDocument/implementation request, same underlying pipeline (see
 * PositionCommandSupport.goToAndFormat()). Takes a leading <what> parameter,
 * like find_declaration/find_reference - unlike find_reference though, <what>
 * genuinely distinguishes two different questions even though only one LSP
 * request exists: pointed at a type, "who implements this interface/extends
 * this abstract class"; pointed at a method, "who overrides this". jdtls
 * itself resolves which one applies from the position alone, but <what>
 * documents which question the caller expects answered, and its literal
 * value is validated the same way as in find_declaration/find_reference.
 */
public class FindImplementationCommand extends Command {

	@Keyword("find_implementation")
	@Help("Finds classes/methods that implement or override a symbol - <what> is method or type, <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				find_implementation - find what implements or overrides a symbol

			SYNOPSIS
				find_implementation <what> <symbol>

			DESCRIPTION
				Sends textDocument/implementation to jdtls for <symbol> -
				<file path>:<line>:<name>, name located as a whole word on
				line of file path - and reports which concrete classes or
				methods actually implement or override it: the
				polymorphism question a plain grep can't answer. <what>
				states which of the two related questions is being asked
				- "type" (which classes implement this interface or
				extend this abstract class) or "method" (which concrete
				methods override this one) - even though both send the
				same LSP request; jdtls resolves which applies from the
				position alone, but <what>'s own literal value is still
				checked ("method" or "type", nothing else) before any
				request is sent, same as find_declaration/find_reference.
				Replaces the former goto_implementation command, which
				had no <what> parameter.

			ERRORS
				<what> must be exactly "method" or "type" - anything else
				is rejected before any jdtls request is sent. <symbol>
				must parse as <file path>:<line>:<name> - the file must
				exist under the project root, line must be within it, and
				name must appear on it as a whole word. The daemon checks
				all of this before find_implementation ever runs, and
				reports whichever check fails first.

			SEE ALSO
				find_declaration(1), find_reference(1), find_symbol(1)
			""")
	public FindImplementationCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String what = params[0];
		if (what.equals("method") == false && what.equals("type") == false)
			return CommandResult.error("Invalid <what> '" + what + "' - expected \"method\" or \"type\"");

		return PositionCommandSupport.goToAndFormat(context, "find_implementation", "textDocument/implementation",
				params[1], null);
	}

}
