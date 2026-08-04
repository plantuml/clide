package clide.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de Listing - le plafonnement des résultats et le comptage qui va avec.
 *
 * Toutes les valeurs attendues ci-dessous sont dérivées à la main de la liste
 * d'entrée, jamais capturées depuis Listing lui-même : un golden pris sur le
 * code testé fige le bug du jour de la capture (cf. JAVALENSE.md §7).
 *
 * Le cas qui compte le plus est celui de "exactement maxResults" : c'est
 * précisément là que compter le plafond au lieu du vrai total fait déclarer
 * tronquée une réponse complète.
 */
class ListingTest {

	private static List<String> letters(final int count) {
		final List<String> items = new ArrayList<>();
		for (int i = 0; i < count; i++)
			items.add("item" + i);

		return items;
	}

	@Test
	@DisplayName("en dessous du plafond, rien n'est tronqué et tout est rendu")
	void underTheCap() {
		final Listing<String> listing = Listing.of(letters(3), 10);

		assertEquals(3, listing.totalCount());
		assertEquals(3, listing.returnedCount());
		assertFalse(listing.truncated());
		assertEquals(List.of("item0", "item1", "item2"), listing.items());
	}

	@Test
	@DisplayName("exactement maxResults n'est PAS tronqué - il n'y avait rien de plus")
	void exactlyTheCapIsNotTruncated() {
		final Listing<String> listing = Listing.of(letters(5), 5);

		assertEquals(5, listing.totalCount());
		assertEquals(5, listing.returnedCount());
		assertFalse(listing.truncated());
	}

	@Test
	@DisplayName("au dessus du plafond, le total reste exact et la troncature est signalée")
	void aboveTheCap() {
		final Listing<String> listing = Listing.of(letters(312), 50);

		assertEquals(312, listing.totalCount());
		assertEquals(50, listing.returnedCount());
		assertTrue(listing.truncated());
		assertEquals("item0", listing.items().get(0));
		assertEquals("item49", listing.items().get(49));
	}

	@Test
	@DisplayName("maxResults 0 rend zéro entrée sans mentir sur le total")
	void zeroIsHonouredLiterally() {
		final Listing<String> listing = Listing.of(letters(7), 0);

		assertEquals(7, listing.totalCount());
		assertEquals(0, listing.returnedCount());
		assertTrue(listing.truncated());
		assertTrue(listing.isEmpty());
	}

	@Test
	@DisplayName("maxResults 0 sur une liste vide ne se déclare pas tronqué")
	void zeroOnNothingIsNotTruncated() {
		final Listing<String> listing = Listing.of(List.of(), 0);

		assertEquals(0, listing.totalCount());
		assertFalse(listing.truncated());
	}

	@Test
	@DisplayName("summarize() ne parle de troncature que lorsqu'il y en a une")
	void summarizeWording() {
		assertEquals("3 location(s)", Listing.of(letters(3), 10).summarize("location"));
		assertEquals("5 location(s)", Listing.of(letters(5), 5).summarize("location"));
		assertEquals("50 location(s) shown out of 312, truncated - raise the limit with set_max_results",
				Listing.of(letters(312), 50).summarize("location"));
	}

	@Test
	@DisplayName("les items sont recopiés - modifier la liste d'origine ne change rien")
	void itemsAreCopied() {
		final List<String> source = new ArrayList<>(List.of("a", "b"));
		final Listing<String> listing = Listing.of(source, 10);
		source.add("c");

		assertEquals(2, listing.returnedCount());
		assertThrows(UnsupportedOperationException.class, () -> listing.items().add("d"));
	}

	@Test
	@DisplayName("un total inférieur au nombre d'items rendus est refusé")
	void totalCannotBeSmallerThanWhatIsReturned() {
		assertThrows(IllegalArgumentException.class, () -> new Listing<>(List.of("a", "b"), 1, 10));
	}

	@Test
	@DisplayName("un total ou un plafond négatif est refusé")
	void negativesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> new Listing<>(List.of(), -1, 10));
		assertThrows(IllegalArgumentException.class, () -> new Listing<>(List.of(), 0, -1));
	}

}
