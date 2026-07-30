package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.core.Symbol;
import clide.jdtls.JdtlsSession;

/**
 * textDocument/hover: the signature/Javadoc jdtls knows for one specific
 * symbol - <symbol> as <file path>:<line>:<name> (see Symbol,
 * ParamType.SYMBOL), same notation as goto_* and list_members. Doesn't share
 * GotoPositionCommand: goto_* results are lists of Location, hover's is a
 * single blob of (usually Markdown) text - a different enough shape that it
 * gets its own thin Command instead.
 *
 * Meant for the case goto_* doesn't cover: a call site already found (e.g. via
 * search_regex or find_symbol) whose exact resolved signature is wanted,
 * without hunting down and reading its declaration by hand.
 */
public class HoverCommand extends Command {

	@Keyword("hover")
	@Help("Shows the signature/Javadoc jdtls knows for a symbol - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	public HoverCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Symbol symbol;
		try {
			symbol = Symbol.parse(params[0], context.getProjectRoot());
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
