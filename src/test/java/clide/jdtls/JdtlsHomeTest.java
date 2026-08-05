package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de JdtlsHome : le choix de l'emplacement où jdtls est extrait.
 *
 * Ce qui est vérifié ici est une décision, pas une extraction : aucune archive
 * n'est lue, rien n'est écrit sur le disque. L'environnement (os.name, HOME,
 * LOCALAPPDATA, XDG_CACHE_HOME) est passé en paramètre plutôt que lu, ce qui
 * permet de tester les trois plateformes depuis n'importe laquelle - un test
 * qui ne vaudrait que sur la machine qui l'exécute ne dirait rien de la
 * portabilité, qui est précisément l'objet de ce code.
 */
class JdtlsHomeTest {

	private static final Path HOME = Paths.get("/home/foo");

	private static Function<String, String> env(final Map<String, String> values) {
		return values::get;
	}

	private static Function<String, String> noEnv() {
		return name -> null;
	}

	@Test
	@DisplayName("Linux sans XDG_CACHE_HOME : ~/.cache/clide")
	void linuxDefault() {
		assertEquals(HOME.resolve(".cache").resolve("clide"), JdtlsHome.cacheRoot("Linux", HOME, noEnv()));
	}

	@Test
	@DisplayName("Linux avec XDG_CACHE_HOME : la variable gagne")
	void linuxHonoursXdg() {
		final Function<String, String> env = env(Map.of("XDG_CACHE_HOME", "/var/cache/me"));

		assertEquals(Paths.get("/var/cache/me", "clide"), JdtlsHome.cacheRoot("Linux", HOME, env));
	}

	@Test
	@DisplayName("XDG_CACHE_HOME vide est ignoré, comme s'il n'existait pas")
	void blankXdgIsIgnored() {
		final Function<String, String> env = env(Map.of("XDG_CACHE_HOME", "   "));

		assertEquals(HOME.resolve(".cache").resolve("clide"), JdtlsHome.cacheRoot("Linux", HOME, env));
	}

	@Test
	@DisplayName("Windows : %LOCALAPPDATA%\\clide, jamais ~/.cache")
	void windowsUsesLocalAppData() {
		final Function<String, String> env = env(Map.of("LOCALAPPDATA", "C:\\Users\\foo\\AppData\\Local"));

		final Path root = JdtlsHome.cacheRoot("Windows 11", HOME, env);

		assertEquals(Paths.get("C:\\Users\\foo\\AppData\\Local", "clide"), root);
		assertFalse(root.toString().contains(".cache"));
	}

	@Test
	@DisplayName("Windows sans LOCALAPPDATA : repli sur AppData/Local sous le home")
	void windowsFallsBackUnderHome() {
		assertEquals(HOME.resolve("AppData").resolve("Local").resolve("clide"),
				JdtlsHome.cacheRoot("Windows 10", HOME, noEnv()));
	}

	@Test
	@DisplayName("macOS : ~/Library/Caches/clide, et XDG_CACHE_HOME n'y change rien")
	void macUsesLibraryCaches() {
		final Function<String, String> env = env(Map.of("XDG_CACHE_HOME", "/var/cache/me"));

		assertEquals(HOME.resolve("Library").resolve("Caches").resolve("clide"),
				JdtlsHome.cacheRoot("Mac OS X", HOME, env));
	}

	@Test
	@DisplayName("aucun emplacement retenu n'est le répertoire courant - c'est tout l'objet du correctif")
	void neverTheWorkingDirectory() {
		for (final String os : new String[] { "Linux", "Windows 11", "Mac OS X" }) {
			final Path root = JdtlsHome.cacheRoot(os, HOME, noEnv());

			assertTrue(root.isAbsolute(), os + " : " + root);
			assertFalse(root.equals(Paths.get("")), os + " : " + root);
			assertFalse(root.startsWith(Paths.get("jdtls")), os + " : " + root);
		}
	}

	@Test
	@DisplayName("le nom du répertoire porte l'empreinte de l'archive, en hexadécimal")
	void directoryNameCarriesTheFingerprint() {
		assertEquals("jdtls-3f2a1b7c", JdtlsHome.directoryName(0x3f2a1b7cL));
		assertEquals("jdtls-0", JdtlsHome.directoryName(0L));
	}

	@Test
	@DisplayName("deux archives différentes ne partagent jamais un répertoire")
	void differentArchivesNeverCollide() {
		assertFalse(JdtlsHome.directoryName(1L).equals(JdtlsHome.directoryName(2L)));
	}
}
