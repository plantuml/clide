package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests des réglages de la JVM de test portés par ClideContext : variables
 * d'environnement, préfixe de classpath et options de la JVM.
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
		assertEquals(List.of(), context.getTestJvmOptions());
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
	@DisplayName("resetTestSettings vide les trois")
	void resetClearsAll(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setTestEnvironment("A", "1");
		context.setTestClasspathPrefix(List.of("a.jar"));
		context.setTestJvmOptions(List.of("-Xint"));

		context.resetTestSettings();

		assertEquals(Map.of(), context.getTestEnvironment());
		assertEquals(List.of(), context.getTestClasspathPrefix());
		assertEquals(List.of(), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("les options de la JVM sont remplacées d'un bloc, et les anciennes sont rendues")
	void jvmOptionsAreReplacedNotAppended(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertEquals(List.of(), context.setTestJvmOptions(List.of("-Xmx64m", "-Xint")));
		assertEquals(List.of("-Xmx64m", "-Xint"), context.setTestJvmOptions(List.of("-XX:+UseSerialGC")));
		assertEquals(List.of("-XX:+UseSerialGC"), context.getTestJvmOptions());
		assertEquals(List.of("-XX:+UseSerialGC"), context.setTestJvmOptions(List.of()));
		assertEquals(List.of(), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("une option refusée ne change rien, et le refus nomme la fautive")
	void refusedJvmOptionChangesNothing(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setTestJvmOptions(List.of("-Xmx64m"));

		// Un mot nu serait lu comme la classe principale ; -cp, -classpath et
		// --class-path écraseraient en silence le classpath que clide assemble ;
		// -jar lancerait autre chose que les tests.
		for (final String bad : List.of("Xmx64m", "", "-cp", "-classpath", "--class-path", "--class-path=a.jar", "-jar")) {
			final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
					() -> context.setTestJvmOptions(List.of("-Xint", bad)), bad);
			assertTrue(refused.getMessage().contains("'" + bad + "'"), refused.getMessage());
		}

		assertEquals(List.of("-Xmx64m"), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("une nouvelle connexion repart sans variable ni préfixe")
	void everyConnectionStartsClean(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		context.setTestEnvironment("VEGA_FORCE_WRITE", "true");
		context.setTestClasspathPrefix(List.of("before.jar"));
		context.setTestJvmOptions(List.of("-Xint"));

		context.resetPerConnectionSettings();

		assertEquals(Map.of(), context.getTestEnvironment());
		assertEquals(List.of(), context.getTestClasspathPrefix());
		assertEquals(List.of(), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("les vues rendues ne se modifient pas de l'extérieur")
	void viewsAreReadOnly(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertThrows(UnsupportedOperationException.class, () -> context.getTestEnvironment().put("A", "1"));
		assertThrows(UnsupportedOperationException.class, () -> context.getTestClasspathPrefix().add("a.jar"));
		assertThrows(UnsupportedOperationException.class, () -> context.getTestJvmOptions().add("-Xint"));
	}

}
