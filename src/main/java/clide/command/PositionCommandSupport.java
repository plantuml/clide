package clide.command;

import java.util.List;

import clide.core.ClideContext;
import clide.core.CommandResult;
import clide.core.Symbol;
import clide.jdtls.JdtlsSession;
import clide.jdtls.Monomorphic;

/**
 * Shared parse-resolve-format pipeline behind every position-based command -
 * find_declaration, find_reference and find_implementation (see
 * FindDeclarationCommand/FindReferenceCommand/FindImplementationCommand) -
 * each of which resolves a <symbol> ("<file path>:<line>:<name>", see Symbol)
 * to a position, sends one LSP request against it, and formats the result the
 * same way; only the LSP method (and, for find_reference, an extra request-
 * level "context") differs between them.
 *
 * Not a Command itself, and no longer a base class either. It used to be
 * (as GotoPositionCommand, an abstract Command subclass fixing lspMethod()/
 * commandName() per subclass) back when goto_definition/goto_type_definition/
 * goto_implementation/goto_references were separate commands, each with a
 * single LSP method fixed at the class level. Now every position-based
 * command picks its LSP method at execution time instead (from a <what>
 * parameter, for find_declaration/find_implementation), so there is no
 * per-class state left to justify a base class - just this one static
 * helper (see CLAUDE.md for the goto_*-to-find_* rename history).
 */
final class PositionCommandSupport {

	private PositionCommandSupport() {
	}

	/**
	 * Resolves symbolText ("<file path>:<line>:<name>") to a Symbol, sends
	 * lspMethod against it (with requestContext merged in if non-null - see
	 * JdtlsSession.goToPosition()), and formats the result: "<count>
	 * location(s)" followed by one "path:line: line content" per result, or
	 * "no definition found" if empty. commandName prefixes both the success
	 * and error messages.
	 */
	static CommandResult goToAndFormat(final ClideContext context, final String commandName, final String lspMethod,
			final String symbolText, final Monomorphic requestContext) {
		final JdtlsSession session = context.getCurrentSession();

		final Symbol symbol;
		try {
			symbol = Symbol.parse(symbolText, context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}

		try {
			return format(commandName, session.goToPosition(lspMethod, symbol, requestContext));
		} catch (final Exception e) {
			return CommandResult.error(commandName + " failed: " + e.getMessage());
		}
	}

	/**
	 * Same parse-then-run pipeline as goToAndFormat(), for the one question that
	 * is not a plain single LSP request: find_implementation on a *method*, which
	 * goes through JdtlsSession.findMethodImplementations() to also recover the
	 * overrides textDocument/implementation drops on generic methods (see that
	 * method for why).
	 */
	static CommandResult findMethodImplementationsAndFormat(final ClideContext context, final String commandName,
			final String symbolText) {
		final JdtlsSession session = context.getCurrentSession();

		final Symbol symbol;
		try {
			symbol = Symbol.parse(symbolText, context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}

		try {
			return format(commandName, session.findMethodImplementations(symbol));
		} catch (final Exception e) {
			return CommandResult.error(commandName + " failed: " + e.getMessage());
		}
	}

	/** "<count> location(s)" then one "path:line: line content" per result. */
	private static CommandResult format(final String commandName, final List<String> locations) {
		if (locations.isEmpty())
			return CommandResult.ok(commandName + ": no definition found");

		final StringBuilder output = new StringBuilder();
		output.append(commandName).append(": ").append(locations.size()).append(" location(s)\n");
		for (final String location : locations)
			output.append(location).append('\n');

		return CommandResult.ok(output.toString().strip());
	}

}
