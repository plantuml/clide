package clide.command;

import java.nio.file.Path;
import java.nio.file.Paths;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsSession;

/**
 * textDocument/hover: the signature/Javadoc jdtls knows for one specific
 * symbol, at <line> in <file path>, locating <symbol> as a whole word on that
 * line - same position resolution as goto_* and list_members. Doesn't share
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
	@Help("Shows the signature/Javadoc jdtls knows for a symbol, at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param(type = ParamType.SINGLE_LINE, description = "File path")
	@Param(type = ParamType.SINGLE_LINE, description = "Line")
	@Param(type = ParamType.SINGLE_LINE, description = "Symbol")
	public HoverCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final String pathArgument = params[0];
		if (pathArgument.isEmpty())
			return CommandResult.error(usage());

		final int line;
		try {
			line = Integer.parseInt(params[1].trim());
		} catch (final NumberFormatException e) {
			return CommandResult.error("Invalid line number: " + params[1]);
		}

		final String symbol = params[2];
		if (symbol.isEmpty())
			return CommandResult.error(usage());

		final Path file = Paths.get(pathArgument).toAbsolutePath().normalize();
		try {
			return CommandResult.ok(session.hover(file, line, symbol));
		} catch (final Exception e) {
			return CommandResult.error("hover failed: " + e.getMessage());
		}
	}

	private String usage() {
		return "Usage: hover <file path> <line> <symbol>";
	}

}
