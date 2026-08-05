package clide.command;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.result.CommandResult;

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
	@Help("Finds classes/methods that implement or override a symbol - <what> is method or type, <position> as <file path>:<line>:<column>:<name>.")
	@Param(type = ParamType.SINGLE_LINE, description = "What: method or type")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_implementation - find what implements or overrides a symbol

			SYNOPSIS
				find_implementation <what> <position>

			DESCRIPTION
				Finds which concrete classes or methods actually implement
				or override a symbol - typically an interface method, an
				abstract method, an interface, or an abstract class: the
				polymorphism question a plain grep can't answer. <position>
				is given as <file path>:<line>:<column>:<name>, name
				starting exactly at column of that line. <what> states which
				question is being asked: "type" (which classes implement
				this interface or extend this abstract class) or "method"
				(which concrete methods override this one).

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
				find_declaration(1), find_reference(1), find_symbol(1)
			""")
	public FindImplementationCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("what", params[0], "method", "type");
		if (rejected != null)
			return rejected;

		// <what> used to be documentation only - jdtls resolves type-vs-method
		// from the position alone. It now genuinely selects a code path: on a
		// method, textDocument/implementation under-reports (it drops erasure and
		// renamed-type-variable overrides of a generic method), so that case goes
		// through a recovering pass - see JdtlsSession.findMethodImplementations().
		// On a type, the plain request is already exhaustive.
		if (params[0].equals("method"))
			return PositionCommandSupport.findMethodImplementations(context, "find_implementation", params[1]);

		return PositionCommandSupport.goTo(context, "find_implementation", "textDocument/implementation", params[1],
				null);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_implementation", result, printMode);
	}

}
