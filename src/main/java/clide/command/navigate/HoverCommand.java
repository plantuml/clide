package clide.command.navigate;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.PositionParser;
import clide.jdtls.JdtlsSession;
import clide.model.Position;

/**
 * textDocument/hover: the signature/Javadoc jdtls knows for the symbol at one
 * specific spot - <position> as <file-content-md5>:<file path>:<line>:<column>:<name> (see Position,
 * ParamType.POSITION), same notation as find_declaration/find_reference/
 * find_implementation and list_members. Doesn't reuse PositionCommandSupport:
 * those commands' results are lists of Location, hover's is a single blob of
 * (usually Markdown) text - a different enough shape that it gets its own
 * thin Command instead.
 *
 * Meant for the case find_declaration/find_reference/find_implementation
 * don't cover: a call site already found (e.g. via search_regex or
 * find_symbol) whose exact resolved signature is wanted, without hunting down
 * and reading its declaration by hand.
 */
public class HoverCommand extends Command {

	@Keyword("hover")
	@Help("Shows the signature/Javadoc jdtls knows for a symbol - <position> as <file-content-md5>:<file path>:<line>:<column>:<name>.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				hover - show the signature/Javadoc jdtls knows for a symbol

			SYNOPSIS
				hover <position>

			DESCRIPTION
				Sends textDocument/hover to jdtls for <position>, given as
				<file-content-md5>:<file path>:<line>:<column>:<name>
				with name starting exactly at that column - the same
				position resolution find_* and list_members use, md5
				included: optional on input (it then means "against the
				file currently on disk"), always printed on output, and
				refused as FILE_MODIFIED when the file has changed since
				the position was produced. Returns a single
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

		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), session, params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "hover failed: " + e.getMessage());
		}

		try {
			// Text, not a parsed structure: what comes back is jdtls' own markdown,
			// "Source:" footer included, and clide passes it through untouched - see
			// CommandPayload.Text.
			final CommandPayload payload = new CommandPayload.Text(session.hover(position));
			return CommandResult.ok(payload);
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "hover failed: " + e.getMessage());
		}
	}

}
