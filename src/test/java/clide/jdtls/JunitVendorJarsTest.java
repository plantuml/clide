package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de JunitVendorJars : l'extraction vers .clide/tmp/jar-junit d'un
 * projet cible des jars que clide.jar embarque, sans jamais toucher au
 * .clide/ du projet lui-même (voir CLAUDE.md).
 *
 * Le vrai clide.jar n'entre jamais en jeu ici : resourceOpener (le rôle que
 * joue ClassLoader::getResourceAsStream en production) est une simple Map en
 * mémoire, ce qui rend la logique copie/idempotence/gitignore testable sans
 * montage - seul le disque (@TempDir, un vrai dossier jetable) est réel.
 */
class JunitVendorJarsTest {

	private static final String ONE_NAME = JunitVendorJars.VENDORED_JAR_NAMES.get(0);

	/** Un faux contenu de jar - son contenu binaire réel n'a aucune importance ici. */
	private static final byte[] FAKE_JAR_BYTES = "not a real jar, just bytes to move around".getBytes(StandardCharsets.UTF_8);

	/** Une resourceOpener qui sert FAKE_JAR_BYTES pour chaque nom de VENDORED_JAR_NAMES. */
	private static Function<String, InputStream> everythingAvailable() {
		return name -> new ByteArrayInputStream(FAKE_JAR_BYTES);
	}

	@Test
	@DisplayName("un jar disponible est extrait tel quel, et son chemin absolu est rendu")
	void extractsWhatIsAvailable(@TempDir final Path projectRoot) throws IOException {
		final List<Path> present = JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		assertEquals(JunitVendorJars.VENDORED_JAR_NAMES.size(), present.size());
		for (final Path jar : present) {
			assertTrue(jar.isAbsolute());
			assertTrue(Files.isRegularFile(jar));
			assertEquals(FAKE_JAR_BYTES.length, Files.size(jar));
		}
	}

	@Test
	@DisplayName("les jars extraits atterrissent bien sous .clide/tmp/jar-junit")
	void landsUnderTheDocumentedDirectory(@TempDir final Path projectRoot) throws IOException {
		final List<Path> present = JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		final Path expectedDir = projectRoot.resolve(JunitVendorJars.TARGET_DIR).toAbsolutePath();
		for (final Path jar : present)
			assertEquals(expectedDir, jar.getParent());
	}

	@Test
	@DisplayName("un nom sans ressource est ignoré en silence, sans exception ni entrée")
	void missingResourceIsSkippedSilently(@TempDir final Path projectRoot) throws IOException {
		final List<Path> present = JunitVendorJars.ensurePresent(projectRoot, name -> null);

		assertTrue(present.isEmpty());
	}

	@Test
	@DisplayName("rien de disponible ne crée même pas le dossier .clide/tmp")
	void nothingAvailableCreatesNothingOnDisk(@TempDir final Path projectRoot) throws IOException {
		JunitVendorJars.ensurePresent(projectRoot, name -> null);

		assertFalse(Files.exists(projectRoot.resolve(".clide")));
	}

	@Test
	@DisplayName("un jar déjà présent n'est pas re-demandé à la resourceOpener")
	void alreadyPresentIsNeverRefetched(@TempDir final Path projectRoot) throws IOException {
		JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		final Set<String> secondCallRequests = new HashSet<>();
		JunitVendorJars.ensurePresent(projectRoot, name -> {
			secondCallRequests.add(name);
			throw new AssertionError("ne doit jamais être appelée pour un jar déjà sur disque : " + name);
		});

		assertTrue(secondCallRequests.isEmpty());
	}

	@Test
	@DisplayName("un second appel rend les mêmes chemins sans rien re-écrire")
	void secondCallIsIdempotent(@TempDir final Path projectRoot) throws IOException {
		final List<Path> first = JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());
		final List<Path> second = JunitVendorJars.ensurePresent(projectRoot,
				name -> new ByteArrayInputStream(new byte[0]));

		assertEquals(first, second);
	}

	@Test
	@DisplayName("un jar déjà extrait pour un nom, manquant pour un autre, coexistent sans se gêner")
	void partialAvailabilityIsFine(@TempDir final Path projectRoot) throws IOException {
		final Map<String, byte[]> available = new HashMap<>();
		available.put(JunitVendorJars.RESOURCE_PREFIX + ONE_NAME, FAKE_JAR_BYTES);

		final List<Path> present = JunitVendorJars.ensurePresent(projectRoot,
				name -> available.containsKey(name) ? new ByteArrayInputStream(available.get(name)) : null);

		assertEquals(1, present.size());
		assertEquals(ONE_NAME, present.get(0).getFileName().toString());
	}

	@Test
	@DisplayName("l'ordre rendu suit VENDORED_JAR_NAMES, pas l'ordre du disque")
	void resultOrderIsDeterministic(@TempDir final Path projectRoot) throws IOException {
		final List<Path> present = JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		for (int i = 0; i < present.size(); i++)
			assertEquals(JunitVendorJars.VENDORED_JAR_NAMES.get(i), present.get(i).getFileName().toString());
	}

	@Test
	@DisplayName("au moins un jar extrait pose un .gitignore ('*') dans .clide/tmp")
	void gitignoreIsCreatedWhenSomethingWasExtracted(@TempDir final Path projectRoot) throws IOException {
		JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		final Path gitignore = projectRoot.resolve(".clide/tmp/.gitignore");
		assertTrue(Files.isRegularFile(gitignore));
		assertEquals("*\n", Files.readString(gitignore, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("un .gitignore déjà là n'est jamais réécrit")
	void gitignoreIsNeverOverwritten(@TempDir final Path projectRoot) throws IOException {
		final Path tmpDir = Files.createDirectories(projectRoot.resolve(".clide/tmp"));
		final Path gitignore = tmpDir.resolve(".gitignore");
		Files.writeString(gitignore, "# personnalisé\n", StandardCharsets.UTF_8);

		JunitVendorJars.ensurePresent(projectRoot, everythingAvailable());

		assertEquals("# personnalisé\n", Files.readString(gitignore, StandardCharsets.UTF_8));
	}
}
