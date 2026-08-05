package clide.command;

import java.util.List;

import clide.PrintMode;
import clide.core.ClideContext;
import clide.core.Monomorphic;
import clide.core.Position;
import clide.jdtls.JdtlsSession;
import clide.result.CodeLocation;
import clide.result.CommandPayload;
import clide.result.CommandResult;
import clide.result.ErrorCode;
import clide.result.Listing;

/**
 * Shared parse-resolve pipeline behind every position-based command -
 * find_declaration, find_reference and find_implementation (see
 * FindDeclarationCommand/FindReferenceCommand/FindImplementationCommand) - each
 * of which parses a &lt;position&gt;
 * ("&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;", see Position) sent by the
 * client, sends one LSP request against it, and builds the same payload from the
 * result; only the LSP method (and, for find_reference, an extra request-level
 * "context") differs between them.
 *
 * It used to format the text too, which is why it was called goToAndFormat().
 * The text now comes from render() below, off the payload, and the two are
 * separate steps: what was found, then how it reads.
 *
 * Not a Command itself, and no longer a base class either - see the git history
 * for the goto_*-to-find_* rename that removed the per-class state a base class
 * existed to hold.
 */
final class PositionCommandSupport {

	private PositionCommandSupport() {
	}

	/**
	 * Resolves positionText to a Position, sends lspMethod against it (with
	 * requestContext merged in if non-null - see JdtlsSession.goToPosition()), and
	 * wraps the locations in a CommandPayload.Locations capped at this
	 * connection's max_results.
	 */
	static CommandResult goTo(final ClideContext context, final String commandName, final String lspMethod,
			final String positionText, final Monomorphic requestContext) {
		final Position position;
		try {
			position = Position.parse(positionText, context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		}

		final JdtlsSession session = context.getCurrentSession();
		try {
			return located(context, position, session.goToPosition(lspMethod, position, requestContext));
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, commandName + " failed: " + e.getMessage());
		}
	}

	/**
	 * Same pipeline, for the one question that is not a plain single LSP request:
	 * find_implementation on a *method*, which goes through
	 * JdtlsSession.findMethodImplementations() to also recover the overrides
	 * textDocument/implementation drops on generic methods (see that method).
	 */
	static CommandResult findMethodImplementations(final ClideContext context, final String commandName,
			final String positionText) {
		final Position position;
		try {
			position = Position.parse(positionText, context.getProjectRoot());
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		}

		final JdtlsSession session = context.getCurrentSession();
		try {
			return located(context, position, session.findMethodImplementations(position));
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, commandName + " failed: " + e.getMessage());
		}
	}

	private static CommandResult located(final ClideContext context, final Position position,
			final List<CodeLocation> locations) {
		final CommandPayload payload = new CommandPayload.Locations(position.name(),
				Listing.of(locations, context.getMaxResults()));
		return CommandResult.ok(payload);
	}

	/**
	 * "&lt;command&gt;: 3 location(s)" then one "path:line:column:name line
	 * content" per result - a whole &lt;position&gt;, a space, the line - or
	 * "&lt;command&gt;: no location found" when there were none.
	 *
	 * Finding nothing is a success, not an error: "this symbol is used nowhere" is
	 * a real answer, and often the one the question was asked for. Only a question
	 * clide could not answer at all is an ERROR - see CommandStatus.
	 */
	static String render(final String commandName, final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.Locations found) {
			final Listing<CodeLocation> locations = found.locations();
			if (locations.totalCount() == 0)
				return commandName + ": no location found";

			final StringBuilder out = new StringBuilder();
			out.append(commandName).append(": ").append(locations.summarize("location"));
			for (final CodeLocation location : locations.items())
				out.append('\n').append(location.display());

			return out.toString();
		}

		return "";
	}

}
