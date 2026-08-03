package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de FileChange - le couple (chemin, type) et sa traduction en FileEvent
 * LSP.
 *
 * Le seul vrai risque de cette classe minuscule est la traduction : le champ
 * "uri" doit être une URI de fichier et non le chemin brut, et le champ "type"
 * doit porter le code numérique du protocole. Une valeur inversée entre CREATED
 * et DELETED compilerait sans broncher ; on vérifie donc les trois codes un par
 * un, écrits en dur ici plutôt que relus depuis l'enum - sinon le test
 * confirmerait simplement que l'enum est égal à lui-même.
 */
class FileChangeTest {

	@Test
	@DisplayName("un chemin null est refusé à la construction")
	void nullPathIsRefused() {
		assertThrows(NullPointerException.class, () -> new FileChange(null, FileChangeType.CREATED));
	}

	@Test
	@DisplayName("un type null est refusé à la construction")
	void nullTypeIsRefused() {
		assertThrows(NullPointerException.class, () -> new FileChange("/tmp/Alpha.java", null));
	}

	@Test
	@DisplayName("fileEvent() porte l'URI du fichier, pas son chemin brut")
	void fileEventCarriesTheUri() {
		final String path = Paths.get("/tmp/projet/Alpha.java").toAbsolutePath().toString();

		final Monomorphic event = new FileChange(path, FileChangeType.CHANGED).fileEvent();

		assertEquals(Paths.get(path).toUri().toString(), event.getFromMap("uri").asString());
	}

	@Test
	@DisplayName("fileEvent() porte le code LSP du type : 1, 2, 3")
	void fileEventCarriesTheLspCode() {
		final String path = Paths.get("/tmp/projet/Alpha.java").toAbsolutePath().toString();

		assertEquals(1, new FileChange(path, FileChangeType.CREATED).fileEvent().getFromMap("type").asInt());
		assertEquals(2, new FileChange(path, FileChangeType.CHANGED).fileEvent().getFromMap("type").asInt());
		assertEquals(3, new FileChange(path, FileChangeType.DELETED).fileEvent().getFromMap("type").asInt());
	}

	@Test
	@DisplayName("deux changements de même chemin et même type sont égaux")
	void equalityIsByValue() {
		assertEquals(new FileChange("/tmp/Alpha.java", FileChangeType.CREATED),
				new FileChange("/tmp/Alpha.java", FileChangeType.CREATED));
		assertNotEquals(new FileChange("/tmp/Alpha.java", FileChangeType.CREATED),
				new FileChange("/tmp/Alpha.java", FileChangeType.DELETED));
		assertNotEquals(new FileChange("/tmp/Alpha.java", FileChangeType.CREATED),
				new FileChange("/tmp/Beta.java", FileChangeType.CREATED));
	}

}
