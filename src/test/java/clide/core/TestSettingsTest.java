package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests des réglages de la JVM de test portés par ClideContext : variables
 * d'environnement et préfixe de classpath.
 *
 * Même enjeu que pour max_results : le seul point visible d'un client est qu'un
 * réglage posé par une session ne survive jamais à cette session. Une variable
 * qui fait réécrire des fichiers de référence à un test, héritée sans que
 * personne l'ait demandée, est bien pire qu'un plafond de résultats oublié.
 */
class TestSettingsTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	@Test
	@DisplayName("au départ, ni variable ni préfixe")
	void startsEmpty(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertEquals(Map.of(), context.getTestEnvironment());
		assertEquals(List.of(), context.getTestClasspathPrefix());
	}

	@Test
	@DisplayName("une variable posée deux fois rend l'ancienne valeur, null la première fois")
	void setReturnsThePreviousValue(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertNull(context.setTestEnvironment("VEGA_FORCE_WRITE", "true"));
		assertEquals("true", context.setTestEnvironment("VEGA_FORCE_WRITE", "false"));
		assertEquals(Map.of("VEGA_FORCE_WRITE", "false"), context.getTestEnvironment());
	}

	@Test
	@DisplayName("un nom qui n'est pas celui d'une variable est refusé, rien n'est posé")
	void invalidNamesAreRefused(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		for (final String name : List.of("", "1ABC", "A-B", "A=B", "A B"))
			assertThrows(IllegalArgumentException.class, () -> context.setTestEnvironment(name, "x"), name);

		assertEquals(Map.of(), context.getTestEnvironment());
	}

	@Test
	@DisplayName("le préfixe est remplacé d'un bloc, et l'ancien est rendu")
	void prefixIsReplacedNotAppended(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertEquals(List.of(), context.setTestClasspathPrefix(List.of("a.jar", "b.jar")));
		assertEquals(List.of("a.jar", "b.jar"), context.setTestClasspathPrefix(List.of("c.jar")));
		assertEquals(List.of("c.jar"), context.getTestClasspathPrefix());
	}

	@Test
	@DisplayName("resetTestSettings vide les deux")
	void resetClearsBoth(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setTestEnvironment("A", "1");
		context.setTestClasspathPrefix(List.of("a.jar"));

		context.resetTestSettings();

		assertEquals(Map.of(), context.getTestEnvironment());
		assertEquals(List.of(), context.getTestClasspathPrefix());
	}

	@Test
	@DisplayName("une nouvelle connexion repart sans variable ni préfixe")
	void everyConnectionStartsClean(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setTestEnvironment("VEGA_FORCE_WRITE", "true");
		context.setTestClasspathPrefix(List.of("before.jar"));

		context.resetPerConnectionSettings();

		assertEquals(Map.of(), context.getTestEnvironment());
		assertEquals(List.of(), context.getTestClasspathPrefix());
	}

	@Test
	@DisplayName("les vues rendues ne se modifient pas de l'extérieur")
	void viewsAreReadOnly(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertThrows(UnsupportedOperationException.class, () -> context.getTestEnvironment().put("A", "1"));
		assertThrows(UnsupportedOperationException.class, () -> context.getTestClasspathPrefix().add("a.jar"));
	}

}
