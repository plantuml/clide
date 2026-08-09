package clide.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

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

	private static final String MD5 = "d41d8cd9";

	/**
	 * La racine du système de fichiers courant - "/" sous Unix, "C:\\" (ou la
	 * lettre du lecteur courant) sous Windows.
	 *
	 * Ce qui suit teste ce que Position et fileIn() font d'un chemin *absolu*, et
	 * ce qui est absolu dépend de la plateforme : "/home/demo/Calc.java" n'a pas
	 * de lettre de lecteur, donc Paths.get(...).isAbsolute() y rend false et
	 * absolutePathIsRejected() n'avait plus rien à refuser. Construire les chemins
	 * à partir de cette racine les rend absolus des deux côtés, sans toucher à ce
	 * que chaque test affirme.
	 */
	private static final Path ROOT = Paths.get("").toAbsolutePath().getRoot();

	@Test
	@DisplayName("un path relatif est accepté")
	void relativePathIsAccepted() {
		new Position(MD5, "src/main/java/demo/Calc.java", 1, 1, "Calc");
	}

	@Test
	@DisplayName("un path absolu est refusé - Paths.get(path).isAbsolute() tranche selon la plateforme courante")
	void absolutePathIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Position(MD5, ROOT.resolve("home").resolve("demo").resolve("Calc.java").toString(), 1, 1, "Calc"));
	}

	@Test
	@DisplayName("une URI file: est refusée, même si elle pointerait dans le projet")
	void fileUriIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Position(MD5, "file:///home/demo/Calc.java", 1, 1, "Calc"));
	}

	@Test
	@DisplayName("null reste accepté - ce n'est pas ce constructeur qui l'interdit")
	void nullPathIsAccepted() {
		new Position(MD5, null, 1, 1, "Calc");
	}

	@Test
	@DisplayName("un md5 null est accepté - une position que personne n'a pu signer")
	void nullMd5IsAccepted() {
		new Position(null, "src/main/java/demo/Calc.java", 1, 1, "Calc");
	}

	@Test
	@DisplayName("un md5 en majuscules est refusé - clide n'imprime qu'une graphie, il n'en accepte qu'une")
	void uppercaseMd5IsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Position(MD5.toUpperCase(Locale.ROOT), "src/Calc.java", 1, 1, "Calc"));
	}

	@Test
	@DisplayName("un md5 de mauvaise longueur est refusé, trop court comme trop long")
	void md5OfTheWrongLengthIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Position(MD5.substring(1), "src/Calc.java", 1, 1, "Calc"));
		assertThrows(IllegalArgumentException.class, () -> new Position(MD5 + "0", "src/Calc.java", 1, 1, "Calc"));
	}

	@Test
	@DisplayName("toString() préfixe le md5, et l'omet - sans deux-points orphelin - quand il n'y en a pas")
	void toStringCarriesTheMd5WhenThereIsOne() {
		assertEquals(MD5 + ":src/Calc.java:12:5:calculer",
				new Position(MD5, "src/Calc.java", 12, 5, "calculer").toString());
		assertEquals("src/Calc.java:12:5:calculer", new Position(null, "src/Calc.java", 12, 5, "calculer").toString());
	}

	@Test
	@DisplayName("fileIn() résout contre la racine du projet")
	void fileInResolvesAgainstTheProjectRoot() {
		final Position position = new Position(MD5, "src/main/java/demo/Calc.java", 12, 5, "calculer");
		final Path projet = ROOT.resolve("home").resolve("foo").resolve("projet");

		assertEquals(projet.resolve("src").resolve("main").resolve("java").resolve("demo").resolve("Calc.java"),
				position.fileIn(projet));
	}

	@Test
	@DisplayName("fileIn() ne dépend pas du répertoire courant de la JVM - c'est tout l'objet de la méthode")
	void fileInIgnoresTheWorkingDirectory() {
		final Position position = new Position(MD5, "src/main/java/demo/Calc.java", 12, 5, "calculer");
		final Path resolved = position.fileIn(ROOT.resolve("home").resolve("foo").resolve("projet"));

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
		final Position position = new Position(MD5, "src/Calc.java", 1, 1, "Calc");

		assertEquals(Paths.get("projet/src/Calc.java"), position.fileIn(Paths.get("projet")));
	}

}
