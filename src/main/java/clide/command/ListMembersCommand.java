package clide.command;

import java.util.List;

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
 * textDocument/documentSymbol: lists the direct members (methods, fields,
 * constructors - not the members of a nested type, only the type itself as a
 * member) of the class/interface/enum named by <symbol> - <file path>:<line>:
 * <name> (see Symbol, ParamType.SYMBOL), same notation as goto_* and hover, but
 * here it identifies which type to inspect rather than where to jump/what to
 * explain. Doesn't share GotoPositionCommand for the same reason hover
 * doesn't: a different result shape (a type's member list, not a list of
 * Location).
 */
public class ListMembersCommand extends Command {

	@Keyword("list_members")
	@Help("Lists the members (methods, fields, constructors) of the class/interface/enum named by <symbol> - <symbol> as <file path>:<line>:<name>.")
	@Param(type = ParamType.SYMBOL, description = "Symbol")
	public ListMembersCommand() {

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
			final List<String> members = session.listMembers(symbol);
			if (members.isEmpty())
				return CommandResult.ok("list_members: " + symbol.name() + " has no members");

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
