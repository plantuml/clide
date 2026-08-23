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
 * The direct superclass and/or interfaces of the class/interface/enum at
 * &lt;position&gt; - one hop up, not the whole hierarchy up to Object
 * (textDocument/prepareTypeHierarchy + typeHierarchy/supertypes). Chainable
 * into another find_supertypes the same way FindCallersCommand's results are
 * - see its doc for the shared one-hop design.
 *
 * Distinct from find_implementation("type", <position>): that one answers
 * "every class that implements/extends this, anywhere below it, all at
 * once" - the opposite direction entirely, and flat rather than one level at
 * a time. find_subtypes is the direction that actually mirrors it (one hop
 * down instead of every implementer at once) - see FindSubtypesCommand.
 */
public class FindSupertypesCommand extends Command {

	@Keyword("find_supertypes")
	@Help("Finds the direct superclass/interfaces of the class/interface/enum at <position> - one hop up, chainable for the next.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_supertypes - find what a type directly extends or implements

			SYNOPSIS
				find_supertypes <position>

			DESCRIPTION
				Finds the direct superclass and/or interfaces of the class,
				interface or enum named at <position>, given as
				<file-content-md5>:<file path>:<line>:<column>:<name>, name
				starting exactly at column of that line. Each result is the
				supertype's own declaration, so it feeds straight back into
				another find_supertypes to climb one level further, or into
				hover/list_members/find_subtypes to ask something else about
				that same supertype.

				Only the direct supertypes are reported - one level, not the
				whole chain up to Object. Walking further is a loop over
				find_supertypes on each result in turn; --lua exists for
				exactly this (see CLAUDE.md).

				A top-level class or interface with nothing above it besides
				(implicitly) Object is a real, successful answer ("no
				location found"), not an error - whether Object itself is
				reported is jdtls' own choice, not clide's.

			ERRORS
				<position> must name a class, interface, or enum - anything
				else (a method, a field) is refused as NOT_A_TYPE.

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
				find_subtypes(1), find_implementation(1), find_declaration(1)
			""")
	public FindSupertypesCommand() {

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
			final List<CodeLocation> supertypes = session.findSupertypes(position);
			return PositionCommandSupport.located(context, position, supertypes);
		} catch (final NotApplicableException e) {
			return CommandResult.error(ErrorCode.NOT_A_TYPE, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a type");
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_supertypes failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_supertypes", result, printMode);
	}

}
