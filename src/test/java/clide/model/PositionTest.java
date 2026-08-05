package clide.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'invariant que Position impose sur son path : relatif au projet,
 * jamais absolu, jamais une URI file:. Voir PositionParser.parse() et
 * JdtlsSession.locationOf() pour les deux seuls producteurs de Position, et
 * comment chacun garantit cet invariant de son côté.
 *
 * Et de sa contrepartie, fileIn() : un path relatif au projet n'a de sens
 * qu'accompagné de la racine de ce projet. Le résoudre avec Paths.get() seul
 * vise le répertoire courant de la JVM - pour le daemon, celui d'où on l'a
 * lancé - et fait répondre « rien trouvé » à des questions parfaitement
 * valides.
 */
class PositionTest {

	@Test
	@DisplayName("un path relatif est accepté")
	void relativePathIsAccepted() {
		new Position("src/main/java/demo/Calc.java", 1, 1, "Calc");
	}

	@Test
	@DisplayName("un path absolu est refusé - Paths.get(path).isAbsolute() tranche selon la plateforme courante")
	void absolutePathIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new Position("/home/demo/Calc.java", 1, 1, "Calc"));
	}

	@Test
	@DisplayName("une URI file: est refusée, même si elle pointerait dans le projet")
	void fileUriIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Position("file:///home/demo/Calc.java", 1, 1, "Calc"));
	}

	@Test
	@DisplayName("null reste accepté - ce n'est pas ce constructeur qui l'interdit")
	void nullPathIsAccepted() {
		new Position(null, 1, 1, "Calc");
	}

	@Test
	@DisplayName("fileIn() résout contre la racine du projet")
	void fileInResolvesAgainstTheProjectRoot() {
		final Position position = new Position("src/main/java/demo/Calc.java", 12, 5, "calculer");

		assertEquals(Paths.get("/home/foo/projet/src/main/java/demo/Calc.java"),
				position.fileIn(Paths.get("/home/foo/projet")));
	}

	@Test
	@DisplayName("fileIn() ne dépend pas du répertoire courant de la JVM - c'est tout l'objet de la méthode")
	void fileInIgnoresTheWorkingDirectory() {
		final Position position = new Position("src/main/java/demo/Calc.java", 12, 5, "calculer");
		final Path resolved = position.fileIn(Paths.get("/home/foo/projet"));

		// Paths.get(path).toAbsolutePath() est ce que faisaient les appelants
		// avant : le même path relatif, mais résolu contre le CWD. Les deux
		// chemins ne peuvent coïncider que si la JVM tourne justement dans le
		// projet - le cas qui masquait le bug.
		assertNotEquals(Paths.get(position.path()).toAbsolutePath(), resolved);
		assertTrue(resolved.isAbsolute());
	}

	@Test
	@DisplayName("fileIn() accepte une racine relative et rend un chemin relatif à celle-ci")
	void fileInAcceptsARelativeRoot() {
		final Position position = new Position("src/Calc.java", 1, 1, "Calc");

		assertEquals(Paths.get("projet/src/Calc.java"), position.fileIn(Paths.get("projet")));
	}

}
