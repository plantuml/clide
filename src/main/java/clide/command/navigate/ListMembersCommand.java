package clide.command.navigate;

import java.io.IOException;

import clide.PrintMode;
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
import clide.core.Position;
import clide.jdtls.JdtlsSession;
import clide.model.Listing;
import clide.model.SymbolHit;

/**
 * textDocument/documentSymbol: lists the direct members (methods, fields,
 * constructors - not the members of a nested type, only the type itself as a
 * member) of the class/interface/enum named by <position> -
 * <file path>:<line>:<column>:<name> (see Position, ParamType.POSITION),
 * same notation as
 * find_declaration/find_reference/find_implementation and hover, but here it
 * identifies which type to inspect rather than where to jump/what to explain.
 * Doesn't reuse PositionCommandSupport for the same reason hover doesn't: a
 * different result shape (a type's member list, not a list of Location).
 */
public class ListMembersCommand extends Command {

	@Keyword("list_members")
	@Help("Lists the members (methods, fields, constructors) of the class/interface/enum named by <position> - <position> as <file path>:<line>:<column>:<name>.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				list_members - list the members of a class, interface, or enum

			SYNOPSIS
				list_members <position>

			DESCRIPTION
				Sends textDocument/documentSymbol to jdtls and lists the
				direct members - methods, fields, constructors - of the
				class, interface or enum named at <position>, given as
				<file path>:<line>:<column>:<name> with name starting
				exactly at that column: the same position resolution find_*
				and hover use, but here identifying which type to inspect
				rather than where to jump or what to explain.

			SEE ALSO
				hover(1), find_declaration(1)
			""")
	public ListMembersCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position position;
		try {
			position = Position.parse(params[0], context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		}

		try {
			final CommandPayload payload = new CommandPayload.Symbols(position.name(),
					Listing.of(session.listMembers(position), context.getMaxResults()));
			return CommandResult.ok(payload);
		} catch (final IOException e) {
			// listMembers() raises this exact IOException when position names something
			// that is not a class/interface/enum - a mistake worth its own code, since
			// the fix is to point at a type rather than to retry.
			return CommandResult.error(ErrorCode.NOT_A_TYPE, e.getMessage(),
					"find_symbol " + position.name() + " lists where that name is declared as a type");
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "list_members failed: " + e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.Symbols found) {
			final Listing<SymbolHit> members = found.symbols();
			if (members.totalCount() == 0)
				return "list_members: " + found.subject()
						+ " has no direct members (inherited ones are never listed - see man list_members)";

			final StringBuilder out = new StringBuilder();
			out.append("list_members: ").append(members.summarize("member"));
			for (final SymbolHit member : members.items())
				out.append('\n').append(member.display());

			return out.toString();
		}

		return "";
	}

}
