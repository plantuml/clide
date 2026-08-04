package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests du réglage max_results porté par ClideContext - la valeur par défaut,
 * le plafond, et surtout la remise à zéro entre deux connexions.
 *
 * Ce dernier point est le seul qui ait des conséquences visibles pour un
 * client : hériter sans le savoir d'un plafond posé par une session précédente,
 * c'est lire une réponse tronquée en la croyant complète.
 */
class MaxResultsSettingTest {

	private static ClideContext contextOn(final Path root) {
		// La session jdtls n'est jamais touchée ici : ce test ne porte que sur un
		// entier détenu par le contexte.
		return new ClideContext(root, null, List.of());
	}

	@Test
	@DisplayName("le plafond par défaut est 100")
	void defaultIsHundred(@TempDir final Path root) {
		assertEquals(100, ClideContext.DEFAULT_MAX_RESULTS);
		assertEquals(100, contextOn(root).getMaxResults());
	}

	@Test
	@DisplayName("une valeur acceptable est prise telle quelle, 0 compris")
	void valuesAreTakenAsGiven(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		context.setMaxResults(50);
		assertEquals(50, context.getMaxResults());

		context.setMaxResults(0);
		assertEquals(0, context.getMaxResults());

		context.setMaxResults(ClideContext.MAX_RESULTS_CEILING);
		assertEquals(ClideContext.MAX_RESULTS_CEILING, context.getMaxResults());
	}

	@Test
	@DisplayName("une valeur négative est refusée, pas ramenée à 0")
	void negativeIsRefused(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setMaxResults(50);

		assertThrows(IllegalArgumentException.class, () -> context.setMaxResults(-1));
		assertEquals(50, context.getMaxResults());
	}

	@Test
	@DisplayName("une valeur au dessus du plafond est refusée, pas ramenée au plafond")
	void aboveTheCeilingIsRefused(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setMaxResults(50);

		assertThrows(IllegalArgumentException.class,
				() -> context.setMaxResults(ClideContext.MAX_RESULTS_CEILING + 1));
		assertEquals(50, context.getMaxResults());
	}

	@Test
	@DisplayName("une nouvelle connexion repart du défaut, jamais du réglage de la précédente")
	void everyConnectionStartsFromTheDefault(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setMaxResults(5);
		context.requestDisconnect();

		context.resetPerConnectionSettings();

		assertEquals(ClideContext.DEFAULT_MAX_RESULTS, context.getMaxResults());
		assertEquals(false, context.isDisconnectRequested());
	}

}
