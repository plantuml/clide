package clide.command;

import java.util.List;

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
 * textDocument/documentSymbol: lists the direct members (methods, fields,
 * constructors - not the members of a nested type, only the type itself as a
 * member) of the class/interface/enum named by <position> - <file path>:
 * <line>:<name> (see Position, ParamType.POSITION), same notation as
 * find_declaration/find_reference/find_implementation and hover, but here it
 * identifies which type to inspect rather than where to jump/what to explain.
 * Doesn't reuse PositionCommandSupport for the same reason hover doesn't: a
 * different result shape (a type's member list, not a list of Location).
 */
public class ListMembersCommand extends Command {

	@Keyword("list_members")
	@Help("Lists the members (methods, fields, constructors) of the class/interface/enum named by <position> - <position> as <file path>:<line>:<name>.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Manual("""
			NAME
				list_members - list the members of a class, interface, or enum

			SYNOPSIS
				list_members <file path> <line> <position>

			DESCRIPTION
				Sends textDocument/documentSymbol to jdtls and lists the
				direct members - methods, fields, constructors - of the
				class, interface or enum named at <position>, located as a
				whole word on <line> of <file path>: the same position
				resolution goto_* and hover use, but here identifying which
				type to inspect rather than where to jump or what to explain.

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
			return CommandResult.error(e.getMessage());
		}

		try {
			final List<String> members = session.listMembers(position);
			if (members.isEmpty())
				return CommandResult.ok("list_members: " + position.name() + " has no members");

			final StringBuilder output = new StringBuilder();
			output.append("list_members: ").append(members.size()).append(" member(s)\n");
			for (final String member : members)
				output.append(member).append('\n');

			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error("list_members failed: " + e.getMessage());
		}
	}

}
