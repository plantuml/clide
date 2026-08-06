package clide.command.navigate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.model.Listing;
import clide.model.SymbolHit;

/**
 * Tests of the rendering find_symbol and list_members share (see
 * FindSymbolCommand, ListMembersCommand) - the empty case, which each
 * command words for itself, and the populated case, which both print alike
 * bar the noun ("symbol"/"member").
 */
class SymbolListRenderingTest {

	private static final CommandResult NOT_SYMBOLS = CommandResult.ok(CommandPayload.NOTHING);

	@Test
	@DisplayName("aucun résultat renvoie le message fourni par l'appelant, pas un texte générique")
	void emptyResultUsesTheCallersOwnMessage() {
		final CommandResult result = CommandResult
				.ok(new CommandPayload.Symbols("calculer", Listing.of(List.of(), 100)));

		final String rendered = SymbolListRendering.render("find_symbol", "symbol",
				subject -> "no symbol at all for " + subject, result);

		assertEquals("no symbol at all for calculer", rendered);
	}

	@Test
	@DisplayName("un ou plusieurs résultats : en-tête avec le nom donné, puis un display() par ligne")
	void populatedResultListsEachHit() {
		final SymbolHit method = new SymbolHit("method", "calculer", null);
		final CommandResult result = CommandResult
				.ok(new CommandPayload.Symbols("calculer", Listing.of(List.of(method), 100)));

		final String rendered = SymbolListRendering.render("list_members", "member", subject -> "unused", result);

		assertEquals("list_members: 1 member(s)\n" + method.display(), rendered);
	}

	@Test
	@DisplayName("un autre payload que Symbols rend une chaîne vide, jamais une exception")
	void wrongPayloadShapeRendersEmpty() {
		assertEquals("", SymbolListRendering.render("find_symbol", "symbol", subject -> "unused", NOT_SYMBOLS));
	}

}
