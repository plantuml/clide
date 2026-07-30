package clide.command;

import java.util.List;
import java.util.Map;

import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.core.Symbol;
import clide.jdtls.JdtlsSession;

/**
 * Shared logic behind goto_definition, goto_type_definition, goto_implementation
 * and goto_references: all four take the same single ParamType.SYMBOL
 * parameter (see Symbol, CLAUDE.md) and only differ in which LSP method is
 * sent - and, for goto_references alone, an extra request-level "context" -
 * see JdtlsSession.goToPosition() for the actual request. Concrete subclasses
 * stay thin: their no-arg constructor carries the usual @Keyword/@Help/@Param
 * annotations, and they only implement lspMethod()/commandName() (and, for
 * goto_references, context()).
 *
 * The actual parse-resolve-format pipeline lives in goToAndFormat() (package-
 * private, static) rather than only here: find_declaration/find_reference
 * (see FindDeclarationCommand/FindReferenceCommand) reuse it too, against an
 * lspMethod chosen at runtime from their own <what> parameter rather than a
 * fixed one per class - so it can't stay a plain instance method tied to a
 * single subclass the way it did before those two commands existed.
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
		return goToAndFormat(context, commandName(), lspMethod(), params[0], context());
	}

	/**
	 * Resolves symbolText ("<file path>:<line>:<name>") to a Symbol, sends
	 * lspMethod against it (with requestContext merged in if non-null - see
	 * JdtlsSession.goToPosition()), and formats the result exactly as every
	 * goto_* command already did: "<count> location(s)" followed by one
	 * "path:line: line content" per result, or "no definition found" if empty.
	 * commandName prefixes both the success and error messages, same as before.
	 */
	static CommandResult goToAndFormat(final ClideContext context, final String commandName, final String lspMethod,
			final String symbolText, final Map<String, Object> requestContext) {
		final JdtlsSession session = context.getCurrentSession();

		final Symbol symbol;
		try {
			symbol = Symbol.parse(symbolText, context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}

		try {
			final List<String> locations = session.goToPosition(lspMethod, symbol, requestContext);
			if (locations.isEmpty())
				return CommandResult.ok(commandName + ": no definition found");

			final StringBuilder output = new StringBuilder();
			output.append(commandName).append(": ").append(locations.size()).append(" location(s)\n");
			for (final String location : locations)
				output.append(location).append('\n');

			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error(commandName + " failed: " + e.getMessage());
		}
	}

}
