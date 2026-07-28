package clide.command;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsSession;

/**
 * Shared logic behind goto_definition and goto_type_definition: both take the
 * same three parameters (file path, 1-based line, symbol text) and only differ
 * in which LSP method is sent - see JdtlsSession.goToPosition() for the actual
 * position resolution and request. Concrete subclasses stay thin: their no-arg
 * constructor carries the usual @Keyword/@Help/@Param annotations, and they
 * only implement lspMethod()/commandName().
 */
public abstract class GotoPositionCommand extends Command {

	/** LSP method to send, e.g. "textDocument/definition". */
	protected abstract String lspMethod();

	/** This command's own @Keyword value, used to prefix messages. */
	protected abstract String commandName();

	@Override
	public final CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();
		if (session == null)
			return CommandResult.error("No project open — use open_project first");

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
			final List<String> locations = session.goToPosition(lspMethod(), file, line, symbol);
			if (locations.isEmpty())
				return CommandResult.ok(commandName() + ": no definition found");

			final StringBuilder output = new StringBuilder();
			output.append(commandName()).append(": ").append(locations.size()).append(" location(s)\n");
			for (final String location : locations)
				output.append(location).append('\n');

			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error(commandName() + " failed: " + e.getMessage());
		}
	}

	private String usage() {
		return "Usage: " + commandName() + " <file path> <line> <symbol>";
	}

}
