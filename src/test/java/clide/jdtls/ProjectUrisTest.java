package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProjectUris : une URI renvoyée par jdtls doit être reconnue comme étant du
 * projet quelle que soit son écriture - c'est ce qui manquait quand toutes les
 * références étaient écartées comme "hors du projet" sous Windows.
 */
class ProjectUrisTest {

	private static final String PROJECT = "file:///C:/github/plantuml";

	@Test
	@DisplayName("les écritures Windows d'un même fichier sont toutes reconnues")
	void everyWindowsSpellingIsRecognized() {
		final String[] spellings = { "file:///C:/github/plantuml/src/A.java", "file:///c:/github/plantuml/src/A.java",
				"file:///c%3A/github/plantuml/src/A.java", "file:///C%3A/github/plantuml/src/A.java",
				"file:/C:/github/plantuml/src/A.java", "file://C:/github/plantuml/src/A.java",
				"file:///C:/GitHub/PlantUML/src/A.java" };
		for (final String uri : spellings)
			assertEquals("src/A.java", ProjectUris.relativePath(PROJECT, uri), uri);
	}

	@Test
	@DisplayName("la racine écrite en minuscules reconnaît les URI de jdtls")
	void lowerCaseProjectRoot() {
		assertTrue(ProjectUris.isInside("file:///c:/github/plantuml", "file:///C:/github/plantuml/src/A.java"));
		assertTrue(ProjectUris.isInside("file:///c:/github/plantuml", "file:///c%3A/github/plantuml/src/A.java"));
	}

	@Test
	@DisplayName("la racine elle-même est dans le projet, avec un chemin vide")
	void theRootItself() {
		assertEquals("", ProjectUris.relativePath(PROJECT, "file:///c%3A/github/plantuml"));
		assertEquals("", ProjectUris.relativePath(PROJECT, "file:///C:/github/plantuml/"));
	}

	@Test
	@DisplayName("un autre dossier au nom qui commence pareil n'est pas dans le projet")
	void siblingWithTheSamePrefix() {
		assertFalse(ProjectUris.isInside(PROJECT, "file:///C:/github/plantuml-other/A.java"));
	}

	@Test
	@DisplayName("un autre disque ou un autre dossier n'est pas dans le projet")
	void otherLocation() {
		assertFalse(ProjectUris.isInside(PROJECT, "file:///D:/github/plantuml/A.java"));
		assertFalse(ProjectUris.isInside(PROJECT, "file:///C:/other/A.java"));
	}

	@Test
	@DisplayName("sous Unix, la casse compte")
	void unixIsCaseSensitive() {
		assertEquals("src/A.java", ProjectUris.relativePath("file:///home/me/plantuml", "file:/home/me/plantuml/src/A.java"));
		assertFalse(ProjectUris.isInside("file:///home/me/plantuml", "file:///home/me/PlantUML/src/A.java"));
	}

	@Test
	@DisplayName("les caractères encodés sont décodés, le plus ne devient pas une espace")
	void percentEncodingIsDecoded() {
		assertEquals("my dir/A+B.java",
				ProjectUris.relativePath("file:///home/me/p", "file:///home/me/p/my%20dir/A+B.java"));
	}

	@Test
	@DisplayName("ce qui n'est pas une URI file: n'est pas dans le projet")
	void notAFileUri() {
		assertNull(ProjectUris.pathOf("jrt:/java.base/java/lang/String.class"));
		assertFalse(ProjectUris.isInside(PROJECT, "jrt:/java.base/java/lang/String.class"));
		assertFalse(ProjectUris.isInside(PROJECT, null));
	}
}
