package clide.command.navigate;

import java.util.List;

import clide.PrintMode;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Monomorphic;
import clide.core.PositionParser;
import clide.jdtls.JdtlsSession;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.Position;

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
 * find_callers, find_callees, find_supertypes and find_subtypes (see
 * FindCallersCommand and siblings) share only the second half of this - the
 * CommandPayload.Locations/Listing shape built by located() below and rendered
 * by render() - since their own JdtlsSession methods each need more than one
 * LSP round trip (prepare, then the actual call/type hierarchy request) and so
 * parse their own &lt;position&gt; and call JdtlsSession directly rather than
 * going through goTo().
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
			position = PositionParser.parse(context.getFilesRepository(), positionText);
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
			position = PositionParser.parse(context.getFilesRepository(), positionText);
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

	/**
	 * Wraps a list of locations already computed by the caller into the same
	 * CommandPayload.Locations/Listing shape goTo()/findMethodImplementations()
	 * build below - shared with FindCallersCommand/FindCalleesCommand/
	 * FindSupertypesCommand/FindSubtypesCommand, whose own JdtlsSession methods
	 * are not a single plain LSP request the way the ones behind goTo() are, but
	 * still answer in exactly this shape - so all seven commands render through
	 * the same render() below, and a result from any of them is exactly as
	 * chainable as one from find_reference/find_implementation.
	 */
	static CommandResult located(final ClideContext context, final Position position,
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
		return switch (result.payload()) {
		case CommandPayload.Locations found -> {
			final Listing<CodeLocation> locations = found.locations();
			if (locations.totalCount() == 0)
				yield commandName + ": no location found";

			final StringBuilder out = new StringBuilder();
			out.append(commandName).append(": ").append(locations.summarize("location"));
			for (final CodeLocation location : locations.items())
				out.append('\n').append(location.display());

			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(commandName, result.payload());
		};
	}

}
