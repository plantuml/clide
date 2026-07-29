package clide.command;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsSession;

/**
 * Shared logic behind goto_definition, goto_type_definition, goto_implementation
 * and goto_references: all four take the same three parameters (file path,
 * 1-based line, symbol text) and only differ in which LSP method is sent - and,
 * for goto_references alone, an extra request-level "context" - see
 * JdtlsSession.goToPosition() for the actual position resolution and request.
 * Concrete subclasses stay thin: their no-arg constructor carries the usual
 * @Keyword/@Help/@Param annotations, and they only implement
 * lspMethod()/commandName() (and, for goto_references, context()).
 */
public abstract class GotoPositionCommand extends Command {

	/** LSP method to send, e.g. "textDocument/definition". */
	protected abstract String lspMethod();

	/** This command's own @Keyword value, used to prefix messages. */
	protected abstract String commandName();

	/** Extra LSP request-level "context" object to merge in, or null - only goto_references overrides this. */
	protected Map<String, Object> context() {
		return null;
	}

	@Override
	public final CommandResult executeCommand(final ClideContext context, final String... params) {
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
			final List<String> locations = session.goToPosition(lspMethod(), file, line, symbol, context());
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
