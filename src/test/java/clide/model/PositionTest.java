package clide.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'invariant que Position impose sur son path : relatif au projet,
 * jamais absolu, jamais une URI file:. Voir PositionParser.parse() et
 * JdtlsSession.locationOf() pour les deux seuls producteurs de Position, et
 * comment chacun garantit cet invariant de son côté.
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

}
