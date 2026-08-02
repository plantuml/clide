package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests d'EclipseDescriptorBuilder - comme EclipseProjectFiles, une logique
 * pure système de fichiers (aucun jdtls ici), entièrement testable avec de
 * vrais répertoires temporaires.
 */
class EclipseDescriptorBuilderTest {

	@Test
	@DisplayName(".project embarque le nom du dossier du projet")
	void dotProjectEmbedsTheProjectFolderName(@TempDir final Path projectRoot) throws IOException {
		final Path named = Files.createDirectory(projectRoot.resolve("my-project"));

		final String xml = EclipseDescriptorBuilder.forProject(named).buildDotProject();

		assertTrue(xml.contains("<name>my-project</name>"));
		assertTrue(xml.contains("org.eclipse.jdt.core.javanature"));
	}

	@Test
	@DisplayName("les dossiers conventionnels src/main|test présents sont tous détectés")
	void detectsEveryConventionalFolderThatExists(@TempDir final Path projectRoot) throws IOException {
		Files.createDirectories(projectRoot.resolve("src/main/java"));
		Files.createDirectories(projectRoot.resolve("src/test/java"));
		// src/main/resources et src/test/resources n'existent pas: ils ne doivent
		// pas apparaître.

		final List<String> found = EclipseDescriptorBuilder.forProject(projectRoot).detectSourceFolders();

		assertEquals(List.of("src/main/java", "src/test/java"), found);
	}

	@Test
	@DisplayName("aucun dossier conventionnel : repli sur 'src' s'il existe")
	void fallsBackToPlainSrcWhenNoConventionalLayoutExists(@TempDir final Path projectRoot) throws IOException {
		Files.createDirectory(projectRoot.resolve("src"));

		final List<String> found = EclipseDescriptorBuilder.forProject(projectRoot).detectSourceFolders();

		assertEquals(List.of("src"), found);
	}

	@Test
	@DisplayName("ni layout conventionnel ni 'src' : aucun dossier détecté")
	void detectsNothingWhenNeitherLayoutExists(@TempDir final Path projectRoot) {
		final List<String> found = EclipseDescriptorBuilder.forProject(projectRoot).detectSourceFolders();

		assertTrue(found.isEmpty());
	}

	@Test
	@DisplayName("un dossier de production n'a pas d'attribut test, un dossier de test si")
	void testFoldersAreMarkedAndProductionFoldersAreNot(@TempDir final Path projectRoot) {
		final String xml = EclipseDescriptorBuilder.forProject(projectRoot)
				.buildDotClasspath(List.of("src/main/java", "src/test/java"));

		assertTrue(xml.contains("<classpathentry kind=\"src\" path=\"src/main/java\"/>"),
				"un dossier de production est une entrée simple, sans output ni attribut");
		assertTrue(xml.contains("<classpathentry kind=\"src\" output=\"bin/test\" path=\"src/test/java\">"));
		assertTrue(xml.contains("<attribute name=\"test\" value=\"true\"/>"));
		assertTrue(xml.contains("<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>"));
		assertTrue(xml.contains("<classpathentry kind=\"output\" path=\"bin/main\"/>"));
	}

	@Test
	@DisplayName("sans jar dans .clide/ ni .clide/tmp/jar-junit/, aucune entrée lib")
	void noLibEntriesWithoutAnyJar(@TempDir final Path projectRoot) {
		final String xml = EclipseDescriptorBuilder.forProject(projectRoot).buildDotClasspath(List.of());

		assertFalse(xml.contains("kind=\"lib\""));
	}

	@Test
	@DisplayName("les jars du projet (.clide/) précèdent ceux vendus par clide (.clide/tmp/jar-junit/)")
	void projectOwnJarsComeBeforeVendoredOnes(@TempDir final Path projectRoot) throws IOException {
		final Path clideDir = Files.createDirectories(projectRoot.resolve(".clide"));
		final Path vendorDir = Files.createDirectories(projectRoot.resolve(JunitVendorJars.TARGET_DIR));
		Files.createFile(clideDir.resolve("project-own.jar"));
		Files.createFile(vendorDir.resolve("vendored.jar"));
		// Un fichier non-jar au milieu ne doit pas apparaître.
		Files.createFile(clideDir.resolve("README.txt"));

		final String xml = EclipseDescriptorBuilder.forProject(projectRoot).buildDotClasspath(List.of());

		final int projectJarIndex = xml.indexOf("project-own.jar");
		final int vendoredJarIndex = xml.indexOf("vendored.jar");
		assertTrue(projectJarIndex >= 0, "le jar du projet doit apparaître");
		assertTrue(vendoredJarIndex >= 0, "le jar vendu par clide doit apparaître");
		assertTrue(projectJarIndex < vendoredJarIndex,
				"le jar du projet doit précéder le jar vendu - le projet gagne en cas de doublon");
		assertFalse(xml.contains("README.txt"));
	}

	@Test
	@DisplayName("buildDotClasspath() sans argument détecte lui-même les dossiers source")
	void noArgOverloadDetectsSourceFoldersItself(@TempDir final Path projectRoot) throws IOException {
		Files.createDirectories(projectRoot.resolve("src/main/java"));

		final String withDetection = EclipseDescriptorBuilder.forProject(projectRoot).buildDotClasspath();
		final String withExplicitList = EclipseDescriptorBuilder.forProject(projectRoot)
				.buildDotClasspath(List.of("src/main/java"));

		assertEquals(withExplicitList, withDetection);
	}

}
