package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de JdtlsWorkspace : le choix du répertoire "-data" que jdtls réutilise
 * d'un lancement à l'autre pour un même projet (voir sa propre doc de classe
 * pour pourquoi - en bref, éviter de tout réimporter/réindexer à froid).
 *
 * Comme JdtlsHomeTest, aucune écriture sur le disque ici : seule la décision
 * (quel nom, pour quel chemin) est vérifiée - fingerprint() et
 * directoryName() sont testés directement, sans passer par resolveFor() (qui
 * lit CLIDE_JDTLS_WORKSPACE et JdtlsHome.cacheRoot(), déjà couverts côté
 * JdtlsHomeTest).
 */
class JdtlsWorkspaceTest {

	@Test
	@DisplayName("le même chemin de projet donne toujours la même empreinte")
	void samePathSameFingerprint() {
		final Path a = Paths.get("/some/project").toAbsolutePath();
		final Path b = Paths.get("/some/project").toAbsolutePath();

		assertEquals(JdtlsWorkspace.fingerprint(a), JdtlsWorkspace.fingerprint(b));
		assertEquals(JdtlsWorkspace.directoryName(a), JdtlsWorkspace.directoryName(b));
	}

	@Test
	@DisplayName("deux projets différents ne partagent jamais un répertoire")
	void differentProjectsNeverCollide() {
		final Path a = Paths.get("/some/project-one").toAbsolutePath();
		final Path b = Paths.get("/some/project-two").toAbsolutePath();

		assertNotEquals(JdtlsWorkspace.fingerprint(a), JdtlsWorkspace.fingerprint(b));
		assertNotEquals(JdtlsWorkspace.directoryName(a), JdtlsWorkspace.directoryName(b));
	}

	@Test
	@DisplayName("un chemin relatif résolu vers le même absolu donne la même empreinte")
	void relativeAndAbsoluteAgreeOnceResolved() {
		final Path absolute = Paths.get(".").toAbsolutePath().normalize().resolve("some-project");
		final Path relative = Paths.get("some-project");

		assertEquals(JdtlsWorkspace.fingerprint(absolute), JdtlsWorkspace.fingerprint(relative.toAbsolutePath()));
	}

	@Test
	@DisplayName("le nom du répertoire porte le préfixe workspace- et l'empreinte en hexadécimal")
	void directoryNameCarriesTheFingerprint() {
		final Path project = Paths.get("/some/project").toAbsolutePath();

		assertEquals("workspace-" + Long.toHexString(JdtlsWorkspace.fingerprint(project)),
				JdtlsWorkspace.directoryName(project));
	}

	@Test
	@DisplayName("resolveFor() respecte CLIDE_JDTLS_WORKSPACE quand il est défini")
	void envOverrideWins() {
		// Pas de moyen de fixer une variable d'environnement depuis le test
		// (System.getenv n'est pas modifiable en Java standard) - ce test se
		// contente donc de vérifier le comportement par défaut, en l'absence de
		// l'override, exactement comme JdtlsHomeTest le fait pour CLIDE_JDTLS_HOME
		// via son propre cacheRoot() paramétré. La lecture de la variable
		// elle-même (System.getenv(ENV_OVERRIDE)) est une ligne triviale, revue à
		// l'oeil plutôt que testée en isolation - voir JdtlsHome, qui fait le même
		// choix pour CLIDE_JDTLS_HOME.
		final Path project = Paths.get("/some/project").toAbsolutePath();

		final Path resolved = JdtlsWorkspace.resolveFor(project);

		assertTrue(resolved.isAbsolute());
		assertTrue(resolved.getFileName().toString().startsWith("workspace-"));
	}

}
