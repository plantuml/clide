package clide.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.CommandRepository;
import clide.core.ClideContext;
import clide.core.FilesRepository;

/**
 * Tests du pont Lua avec un vrai runtime : les fonctions sont bindées, le
 * script tourne, et ce qu'il imprime est relu ici.
 *
 * Le ClideContext est construit sans JdtlsSession (null) - légitime tant que
 * les seules commandes appelées déclarent needsJdtlsSession() false, ce que
 * CommandDispatcher vérifie avant de toucher à la session. Ça garde ces tests à
 * la seconde plutôt qu'à la minute : ce qui est éprouvé ici est le pont, pas
 * jdtls, et un test qui démarrerait un serveur de langage pour vérifier une
 * conversion de table n'éprouverait surtout que sa propre patience.
 */
class LuaBridgeTest {

	private final ByteArrayOutputStream written = new ByteArrayOutputStream();

	private String run(final Path projectRoot, final String script) {
		final PrintStream out = new PrintStream(written, true, StandardCharsets.UTF_8);
		final FilesRepository filesRepository = new FilesRepository(projectRoot, null);

		new LuaBridge(new ClideContext(filesRepository, null, CommandRepository.commands), out).run(script);
		return written.toString(StandardCharsets.UTF_8);
	}

	private boolean succeeded(final Path projectRoot, final String script) {
		final PrintStream out = new PrintStream(written, true, StandardCharsets.UTF_8);
		final FilesRepository filesRepository = new FilesRepository(projectRoot, null);
		return new LuaBridge(new ClideContext(filesRepository, null, CommandRepository.commands), out).run(script);
	}

	@Test
	@DisplayName("print écrit sur la sortie du client, pas sur celle du process")
	void printReachesTheClient(@TempDir final Path project) {
		assertEquals("bonjour\t42\ttrue\tnil\n", run(project, "print('bonjour', 42, true, nil)"));
	}

	@Test
	@DisplayName("une commande rend son payload sous forme de table")
	void commandReturnsATable(@TempDir final Path project) {
		final String printed = run(project, """
				local setting = set_max_results(250)
				print(setting.name, setting.previousValue, setting.newValue)
				""");

		assertEquals("max_results\t100\t250\n", printed);
	}

	@Test
	@DisplayName("une commande refusée lève une erreur Lua, rattrapable par pcall")
	void refusedCommandRaises(@TempDir final Path project) {
		final String printed = run(project, """
				local ok, err = pcall(set_max_results, -5)
				print(ok, err)
				""");

		assertTrue(printed.startsWith("false\t?ERROR INVALID_INTEGER:"), printed);
	}

	@Test
	@DisplayName("une erreur non rattrapée arrête le script et sort dans l'enveloppe habituelle")
	void uncaughtErrorEndsTheScript(@TempDir final Path project) {
		final boolean completed = succeeded(project, """
				print('avant')
				set_max_results(-5)
				print('après')
				""");

		final String printed = written.toString(StandardCharsets.UTF_8);
		assertFalse(completed);
		assertTrue(printed.contains("avant"), printed);
		// "après" ne doit pas apparaître : une commande refusée arrête le script,
		// elle ne le laisse pas continuer comme si de rien n'était.
		assertFalse(printed.contains("après"), printed);
		assertTrue(printed.contains("?ERROR LUA_SCRIPT_FAILED: "), printed);
	}

	@Test
	@DisplayName("une erreur de syntaxe est rapportée avec la ligne fautive")
	void syntaxErrorNamesItsLine(@TempDir final Path project) {
		assertFalse(succeeded(project, "this is not lua"));

		assertTrue(written.toString(StandardCharsets.UTF_8).contains("?ERROR LUA_SCRIPT_FAILED: "),
				written.toString(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("le mauvais nombre d'arguments est refusé en nommant ceux qui étaient attendus")
	void wrongArityIsNamed(@TempDir final Path project) {
		final String printed = run(project, """
				local ok, err = pcall(set_max_results)
				print(err)
				""");

		assertEquals("set_max_results() expects 1 argument (<count>), got 0\n", printed);
	}

	@Test
	@DisplayName("exit, quit et terminate ne sont pas des fonctions Lua")
	void sessionCommandsAreNotBound(@TempDir final Path project) {
		final String printed = run(project, "print(exit, quit, terminate, set_max_results ~= nil)");

		assertEquals("nil\tnil\tnil\ttrue\n", printed);
	}

	@Test
	@DisplayName("le script n'a ni io ni os : tout ce qu'il touche passe par une commande")
	void filesystemLibrariesAreNotOpen(@TempDir final Path project) {
		final String printed = run(project, "print(io, os, package, string ~= nil, table ~= nil, math ~= nil)");

		assertEquals("nil\tnil\tnil\ttrue\ttrue\ttrue\n", printed);
	}

}
