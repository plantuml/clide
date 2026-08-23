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
 * The direct subclasses/implementors of the class/interface/enum at
 * &lt;position&gt; - find_supertypes' opposite direction (typeHierarchy/subtypes
 * rather than supertypes). See FindSupertypesCommand's doc for how this
 * differs from find_implementation("type", <position>): find_subtypes is one
 * hop down and chainable, find_implementation is every implementer at once,
 * flat.
 */
public class FindSubtypesCommand extends Command {

	@Keyword("find_subtypes")
	@Help("Finds the direct subclasses/implementors of the class/interface/enum at <position> - one hop down, chainable for the next.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				find_subtypes - find what directly extends or implements a type

			SYNOPSIS
				find_subtypes <position>

			DESCRIPTION
				Finds the direct subclasses and/or implementors of the class,
				interface or enum named at <position>, given as
				<file-content-md5>:<file path>:<line>:<column>:<name>, name
				starting exactly at column of that line. Each result is the
				subtype's own declaration, so it feeds straight back into
				another find_subtypes to go one level further down, or into
				hover/list_members/find_supertypes to ask something else
				about that same subtype.

				Only the direct subtypes are reported - one level, not every
				subtype below them too. Walking further is a loop over
				find_subtypes on each result in turn; --lua exists for
				exactly this (see CLAUDE.md). find_implementation("type",
				<position>) is the flat alternative: every implementer/
				subclass anywhere below <position>, in one answer, with no
				per-level position to chain from.

				A type with nothing extending or implementing it is a real,
				successful answer ("no location found"), not an error.

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
				find_supertypes(1), find_implementation(1), find_declaration(1)
			""")
	public FindSubtypesCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), session, params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_subtypes failed: " + e.getMessage());
		}

		try {
			final List<CodeLocation> subtypes = session.findSubtypes(position);
			return PositionCommandSupport.located(context, position, subtypes);
		} catch (final NotApplicableException e) {
			return CommandResult.error(ErrorCode.NOT_A_TYPE, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a type");
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_subtypes failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return PositionCommandSupport.render("find_subtypes", result, printMode);
	}

}
