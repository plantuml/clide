package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.core.Position;
import clide.jdtls.JdtlsSession;

/**
 * textDocument/hover: the signature/Javadoc jdtls knows for one specific symbol
 * - <symbol> as <file path>:<line>:<name> (see Symbol, ParamType.SYMBOL), same
 * notation as find_declaration/find_reference/find_implementation and
 * list_members. Doesn't reuse PositionCommandSupport: those commands' results
 * are lists of Location, hover's is a single blob of (usually Markdown) text -
 * a different enough shape that it gets its own thin Command instead.
 *
 * Meant for the case find_declaration/find_reference/find_implementation
 * don't cover: a call site already found (e.g. via search_regex or
 * find_symbol) whose exact resolved signature is wanted, without hunting down
 * and reading its declaration by hand.
 */
public class HoverCommand extends Command {

	@Keyword("hover")
	@Help("Shows the signature/Javadoc jdtls knows for a symbol - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	@Manual("""
			NAME
				hover - show the signature/Javadoc jdtls knows for a symbol

			SYNOPSIS
				hover <file path> <line> <symbol>

			DESCRIPTION
				Sends textDocument/hover to jdtls for <symbol>, located as a
				whole word on <line> of <file path> - the same position
				resolution goto_* and list_members use. Returns a single
				blob of text, usually Markdown: the resolved signature and
				whatever Javadoc jdtls knows for it, without having to open
				and read the symbol's own declaration by hand. Meant for a
				call site already found - e.g. via search_regex or
				find_symbol - whose exact resolved signature is wanted; use
				find_declaration instead to jump to that declaration itself.

			SEE ALSO
				find_declaration(1), list_members(1)
			""")
	public HoverCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position symbol;
		try {
			symbol = Position.parse(params[0], context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}

		try {
			return CommandResult.ok(symbol.retrieveJavadoc(session));
		} catch (final Exception e) {
			return CommandResult.error("hover failed: " + e.getMessage());
		}
	}

}
