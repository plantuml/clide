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
 * Every method/constructor that directly calls the one at &lt;position&gt; - the
 * call hierarchy question ("who calls this") HISTORY.md flagged as a future
 * command once jdtls' support for it was confirmed (textDocument/
 * prepareCallHierarchy + callHierarchy/incomingCalls). One hop, not the whole
 * call graph: each result is the caller's own declaration position, directly
 * chainable into another find_callers to walk up a level further - the same
 * pattern the Lua scripting example in CLAUDE.md already uses to walk
 * find_reference in a loop, rather than clide trying to answer a multi-level
 * question in one round trip.
 *
 * Unlike find_declaration/find_reference/find_implementation, takes no
 * leading &lt;what&gt;: the primary use is a method or constructor position, and
 * jdtls itself decides what a call hierarchy question resolves to (see this
 * class' own Manual for what a field or a type position answers with
 * instead of being refused) - there is nothing left for an enum parameter to
 * disambiguate either way, so adding one here would be pure friction, not
 * documentation.
 */
public class FindCallersCommand extends Command {

	@Keyword("find_callers")
	@Help("Finds every method/constructor that directly calls the one at <position> - one hop, chainable for the next.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_callers - find who directly calls a method

			SYNOPSIS
				find_callers <position>

			DESCRIPTION
				Finds every method or constructor that directly calls the
				method/constructor named at <position>, given as
				<file-content-md5>:<file path>:<line>:<column>:<name>, name
				starting exactly at column of that line. Each result is the
				calling method's own declaration - not the call site inside
				it - so it feeds straight back into another find_callers to
				trace the call chain one hop further, or into hover/
				find_reference/find_callees to ask something else about that
				same caller.

				Only the direct callers are reported - one level, not the
				whole tree of who calls the callers. Walking further is a
				loop over find_callers on each result in turn; --lua exists
				for exactly this (see CLAUDE.md).

				A method nothing calls is a real, successful answer ("no
				location found"), not an error.

				<position> is meant to name a method or constructor, but
				jdtls' own call hierarchy is lenient about what else it will
				resolve: pointed at a field, it answers with every method
				that reads or writes that field (Eclipse's Call Hierarchy
				view does the same for fields); pointed at a type, with
				every constructor that implicitly or explicitly calls one of
				its own. Both are real, useful answers, not a workaround -
				only a position jdtls cannot place at all (rare - a keyword,
				an import, whitespace) is refused.

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
				find_callees(1), find_reference(1), find_declaration(1)
			""")
	public FindCallersCommand() {

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
			final List<CodeLocation> callers = session.findCallers(position);
			return PositionCommandSupport.located(context, position, callers);
		} catch (final NotApplicableException e) {
			return CommandResult.error(ErrorCode.NOT_A_METHOD, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a method");
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_callers failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_callers", result, printMode);
	}

}
