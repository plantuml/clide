package clide.command.navigate;

import java.util.function.Function;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.model.Listing;
import clide.model.SymbolHit;

/**
 * Shared behind find_symbol and list_members (see FindSymbolCommand,
 * ListMembersCommand), which both answer with a CommandPayload.Symbols and
 * read alike: "<command>: n symbol(s)/member(s)" then one "[kind]
 * path:line:column:name line content" per hit - or, when there are none, a
 * message specific to that command (find_symbol's names its own jdtls blind
 * spot; list_members' explains that inherited members are never listed).
 * emptyMessage takes the payload's own subject (the name searched for, or
 * the type inspected) so each command can still word its own case, without
 * repeating the rest.
 */
final class SymbolListRendering {

	private SymbolListRendering() {
	}

	static String render(final String commandName, final String noun, final Function<String, String> emptyMessage,
			final CommandResult result) {
		return switch (result.payload()) {
		case CommandPayload.Symbols found -> {
			final Listing<SymbolHit> symbols = found.symbols();
			if (symbols.totalCount() == 0)
				yield emptyMessage.apply(found.subject());

			final StringBuilder out = new StringBuilder();
			out.append(commandName).append(": ").append(symbols.summarize(noun));
			for (final SymbolHit symbol : symbols.items())
				out.append('\n').append(symbol.display());

			yield out.toString();
		}
		default -> "";
		};
	}

}
