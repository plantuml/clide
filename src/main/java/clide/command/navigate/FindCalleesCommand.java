package clide.command.navigate;

import java.io.IOException;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.PositionParser;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;
import clide.jdtls.NotApplicableException;
import clide.model.CodeLocation;
import clide.model.Position;

/**
 * Every method/constructor that the one at &lt;position&gt; directly calls -
 * find_callers' opposite direction (callHierarchy/outgoingCalls rather than
 * incomingCalls), and the one of the two with no equivalent anywhere else in
 * clide today: find_reference answers "who uses this symbol", never "what
 * does this one call". See FindCallersCommand's doc for the one-hop,
 * chainable-position design shared by both.
 */
public class FindCalleesCommand extends Command {

	@Keyword("find_callees")
	@Help("Finds every method/constructor that the one at <position> directly calls - one hop, chainable for the next.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_callees - find what a method directly calls

			SYNOPSIS
				find_callees <position>

			DESCRIPTION
				Finds every method or constructor that the method/
				constructor named at <position> directly calls, given as
				<file-content-md5>:<file path>:<line>:<column>:<name>, name
				starting exactly at column of that line. Each result is the
				called method's own declaration, so it feeds straight back
				into another find_callees to trace what it in turn calls,
				or into hover/find_reference/find_callers to ask something
				else about that same callee.

				Only the direct callees are reported - one level, not the
				whole tree of what they call in turn. Walking further is a
				loop over find_callees on each result in turn; --lua exists
				for exactly this (see CLAUDE.md).

				A method that calls nothing (a getter, an abstract method's
				own declaration) is a real, successful answer ("no location
				found"), not an error.

				<position> is meant to name a method or constructor, but
				jdtls' own call hierarchy is lenient about what else it will
				resolve - see find_callers' Manual for what a field or a
				type position answers with instead of being refused.

			ERRORS
				<position> is refused as NOT_A_METHOD when jdtls' own
				textDocument/prepareCallHierarchy cannot place it at all -
				see the note above for what still resolves.

				<position> must parse as
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
				find_callers(1), find_reference(1), find_declaration(1)
			""")
	public FindCalleesCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		}

		final JdtlsSession session = context.getCurrentSession();
		try {
			final List<CodeLocation> callees = session.findCallees(position);
			return PositionCommandSupport.located(context, position, callees);
		} catch (final NotApplicableException e) {
			return CommandResult.error(ErrorCode.NOT_A_METHOD, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a method");
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_callees failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_callees", result, printMode);
	}

}
