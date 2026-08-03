package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de Delta - le conteneur de FileChange, indépendamment de tout
 * instantané : on lui donne directement les changements, aucun fichier n'est
 * touché ici. Le diff lui-même est testé dans SnapshotDeltaTest.
 *
 * Ce qui est vérifié tient en quatre promesses : le tri par chemin (un Snapshot
 * itère une HashMap, donc l'ordre d'arrivée est arbitraire et ne doit pas se
 * voir de l'extérieur), l'immuabilité (une copie à la construction, et rien de
 * modifiable rendu), le refus de deux changements pour un même fichier (un diff
 * qui se contredit), et la traduction en événements LSP dans ce même ordre.
 */
class DeltaTest {

	private static final FileChange ALPHA_CREATED = new FileChange("/p/Alpha.java", FileChangeType.CREATED);
	private static final FileChange BETA_CHANGED = new FileChange("/p/Beta.java", FileChangeType.CHANGED);
	private static final FileChange GAMMA_DELETED = new FileChange("/p/Gamma.java", FileChangeType.DELETED);

	@Test
	@DisplayName("empty() ne contient rien")
	void emptyIsEmpty() {
		assertTrue(Delta.empty().isEmpty());
		assertEquals(0, Delta.empty().size());
		assertTrue(Delta.empty().changes().isEmpty());
		assertTrue(Delta.empty().fileEvents().isEmpty());
	}

	@Test
	@DisplayName("of() sans aucun changement vaut empty()")
	void ofNothingIsEmpty() {
		assertEquals(Delta.empty(), Delta.of());
		assertEquals(Delta.empty(), Delta.of(List.of()));
	}

	@Test
	@DisplayName("un delta non vide n'est pas vide et sait combien de fichiers ont bougé")
	void sizeCountsAllThreeKinds() {
		final Delta delta = Delta.of(ALPHA_CREATED, BETA_CHANGED, GAMMA_DELETED);

		assertFalse(delta.isEmpty());
		assertEquals(3, delta.size());
	}

	@Test
	@DisplayName("les changements sortent triés par chemin, quel que soit l'ordre d'entrée")
	void changesAreSortedByPath() {
		final Delta delta = Delta.of(GAMMA_DELETED, ALPHA_CREATED, BETA_CHANGED);

		assertEquals(List.of(ALPHA_CREATED, BETA_CHANGED, GAMMA_DELETED), delta.changes());
	}

	@Test
	@DisplayName("deux deltas construits des mêmes changements sont égaux, même ordre d'entrée différent")
	void equalityIgnoresInputOrder() {
		assertEquals(Delta.of(ALPHA_CREATED, BETA_CHANGED), Delta.of(BETA_CHANGED, ALPHA_CREATED));
		assertEquals(Delta.of(ALPHA_CREATED, BETA_CHANGED).hashCode(),
				Delta.of(BETA_CHANGED, ALPHA_CREATED).hashCode());
		assertNotEquals(Delta.of(ALPHA_CREATED), Delta.of(ALPHA_CREATED, BETA_CHANGED));
	}

	@Test
	@DisplayName("changesOfType() ne rend que le type demandé")
	void changesOfTypeFilters() {
		final FileChange otherCreated = new FileChange("/p/Delta.java", FileChangeType.CREATED);
		final Delta delta = Delta.of(otherCreated, GAMMA_DELETED, ALPHA_CREATED, BETA_CHANGED);

		assertEquals(List.of(ALPHA_CREATED, otherCreated), delta.changesOfType(FileChangeType.CREATED));
		assertEquals(List.of(BETA_CHANGED), delta.changesOfType(FileChangeType.CHANGED));
		assertEquals(List.of(GAMMA_DELETED), delta.changesOfType(FileChangeType.DELETED));
	}

	@Test
	@DisplayName("changesOfType() rend une liste vide quand aucun changement de ce type")
	void changesOfTypeCanBeEmpty() {
		assertTrue(Delta.of(ALPHA_CREATED).changesOfType(FileChangeType.DELETED).isEmpty());
	}

	@Test
	@DisplayName("fileEvents() traduit chaque changement, dans l'ordre des chemins")
	void fileEventsFollowThePathOrder() {
		final Delta delta = Delta.of(GAMMA_DELETED, ALPHA_CREATED, BETA_CHANGED);

		final List<Monomorphic> events = delta.fileEvents();

		assertEquals(3, events.size());
		assertEquals(1, events.get(0).getFromMap("type").asInt());
		assertEquals(2, events.get(1).getFromMap("type").asInt());
		assertEquals(3, events.get(2).getFromMap("type").asInt());
	}

	@Test
	@DisplayName("deux changements pour le même fichier sont refusés")
	void aPathCannotChangeTwice() {
		final FileChange sameFileDeleted = new FileChange(ALPHA_CREATED.path(), FileChangeType.DELETED);

		assertThrows(IllegalArgumentException.class, () -> Delta.of(ALPHA_CREATED, sameFileDeleted));
	}

	@Test
	@DisplayName("un changement null est refusé")
	void aNullChangeIsRefused() {
		final List<FileChange> withNull = new ArrayList<>();
		withNull.add(ALPHA_CREATED);
		withNull.add(null);

		assertThrows(NullPointerException.class, () -> Delta.of(withNull));
	}

	@Test
	@DisplayName("modifier la collection après coup ne change pas le delta")
	void theCollectionIsCopied() {
		final List<FileChange> source = new ArrayList<>(List.of(ALPHA_CREATED));
		final Delta delta = Delta.of(source);

		source.add(BETA_CHANGED);

		assertEquals(1, delta.size());
		assertEquals(List.of(ALPHA_CREATED), delta.changes());
	}

	@Test
	@DisplayName("les listes rendues ne sont pas modifiables")
	void returnedListsAreUnmodifiable() {
		final Delta delta = Delta.of(ALPHA_CREATED);

		assertThrows(UnsupportedOperationException.class, () -> delta.changes().add(BETA_CHANGED));
		assertThrows(UnsupportedOperationException.class,
				() -> delta.changesOfType(FileChangeType.CREATED).add(BETA_CHANGED));
		assertThrows(UnsupportedOperationException.class, () -> delta.fileEvents().clear());
	}

}
