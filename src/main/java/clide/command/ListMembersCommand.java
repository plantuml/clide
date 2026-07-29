package clide.command;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsSession;

/**
 * textDocument/documentSymbol: lists the direct members (methods, fields,
 * constructors) of the class/interface/enum named <symbol>, declared at
 * <line> in <file path> - same whole-word-on-line position resolution as
 * goto_* and hover, but here it identifies which type to inspect rather than
 * where to jump or what to explain. Doesn't share GotoPositionCommand for the
 * same reason hover doesn't: a different result shape (a type's member list,
 * not a list of Location).
 */
public class ListMembersCommand extends Command {

	@Keyword("list_members")
	@Help("Lists the members (methods, fields, constructors) of the class/interface/enum named <symbol>, declared at <line> in <file path>, locating <symbol> as a whole word on that line.")
	@Param("File path")
	@Param("Line")
	@Param("Symbol")
	public ListMembersCommand() {
		// Constructeur
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
			final List<String> members = session.listMembers(file, line, symbol);
			if (members.isEmpty())
				return CommandResult.ok("list_members: " + symbol + " has no members");

			final StringBuilder output = new StringBuilder();
			output.append("list_members: ").append(members.size()).append(" member(s)\n");
			for (final String member : members)
				output.append(member).append('\n');

			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error("list_members failed: " + e.getMessage());
		}
	}

	private String usage() {
		return "Usage: list_members <file path> <line> <symbol>";
	}

}
