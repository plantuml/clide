package clide.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.CommandRepository;
import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.model.Listing;
import clide.model.TestOutcome;
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
		return normalized(written.toString(StandardCharsets.UTF_8));
	}

	/**
	 * La sortie du script, ses fins de ligne ramenées à "\n".
	 *
	 * LuaBridge écrit par PrintStream.println(), qui termine chaque ligne par le
	 * séparateur de la plateforme : "\n" sous Unix, "\r\n" sous Windows. Ce que
	 * ces tests vérifient est ce que print() fait parvenir au client - le texte,
	 * son ordre, ses tabulations - jamais lequel des deux terminateurs la JVM a
	 * choisi. Comparer à "\n" en dur faisait donc échouer cinq tests sous Windows
	 * pour une raison dont aucun ne parle.
	 *
	 * Normalise plutôt que supprime : une ligne qui manquerait sa fin de ligne
	 * reste détectée.
	 */
	private static String normalized(final String output) {
		return output.replace("\r\n", "\n");
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
	@DisplayName("set_test_env rend l'ancienne valeur, et refuse un nom qui n'en est pas un")
	void testEnvironmentFromLua(@TempDir final Path project) {
		final String printed = run(project, """
				local first = set_test_env("VEGA_FORCE_WRITE", "true")
				local second = set_test_env("VEGA_FORCE_WRITE", "false")
				print(first.name, first.previousValue, first.newValue)
				print(second.previousValue, second.newValue)
				local ok, err = pcall(set_test_env, "1BAD", "x")
				print(ok, err)
				""");

		assertTrue(printed.startsWith("test_env.VEGA_FORCE_WRITE\t(unset)\ttrue\ntrue\tfalse\nfalse\t?ERROR VALUE_OUT_OF_RANGE:"),
				printed);
	}

	@Test
	@DisplayName("set_test_classpath_prefix refuse une entrée absente, accepte une existante, et reset la retire")
	void testClasspathPrefixFromLua(@TempDir final Path project) throws IOException {
		Files.write(project.resolve("before.jar"), new byte[0]);

		final String printed = run(project, """
				local ok, err = pcall(set_test_classpath_prefix, "nope.jar")
				print(ok, err:match("^%?ERROR [%u_]+"))
				local set = set_test_classpath_prefix("before.jar")
				print(set.name, set.previousValue == "", set.newValue:match("before%.jar$"))
				reset_test_settings()
				local again = set_test_classpath_prefix("before.jar")
				print(again.previousValue == "")
				""");

		assertEquals("false\t?ERROR FILE_NOT_FOUND\ntest_classpath_prefix\ttrue\tbefore.jar\ntrue\n", printed);
	}

	/** A command answering like run_test on a red suite: an ERROR that still carries the failures. */
	public static class RedRunCommand extends Command {

		@Keyword("red_run")
		@Help("Test stand-in: a run that completed with one failure.")
		public RedRunCommand() {
		}

		@Override
		public boolean needsJdtlsSession() {
			return false;
		}

		@Override
		public CommandResult executeCommand(final ClideContext context, final String... params) {
			final TestOutcome failure = new TestOutcome(TestOutcome.Status.FAILED, "case.puml", "", List.of("boom"), "");
			return CommandResult.error(ErrorCode.TEST_FAILURES, "1 test(s) failed out of 2", "",
					new CommandPayload.TestRun("Demo", 1, 1, 0, 5L, Listing.of(List.of(failure), 100), true));
		}

		@Override
		public String render(final CommandResult result, final PrintMode printMode) {
			return "red_run";
		}
	}

	/** A command that cannot run at all, as a run_test with nothing to run would be. */
	public static class BrokenRunCommand extends RedRunCommand {

		@Keyword("broken_run")
		@Help("Test stand-in: a run that could not happen.")
		public BrokenRunCommand() {
		}

		@Override
		public CommandResult executeCommand(final ClideContext context, final String... params) {
			return CommandResult.error(ErrorCode.TEST_RUNNER_BROKEN, "no runner");
		}
	}

	@Test
	@DisplayName("une suite rouge rend sa table à Lua, avec les tests en échec ; une exécution impossible lève")
	void redRunIsAResultNotARefusal(@TempDir final Path project) {
		final PrintStream out = new PrintStream(written, true, StandardCharsets.UTF_8);
		final ClideContext context = new ClideContext(new FilesRepository(project, null), null,
				List.of(new RedRunCommand(), new BrokenRunCommand()));

		new LuaBridge(context, out).run("""
				local run = red_run()
				print(run.failed, run.tests.items[1].status, run.tests.items[1].name)
				local ok, err = pcall(broken_run)
				print(ok, err)
				""");

		assertEquals("1\tfailed\tcase.puml\nfalse\t?ERROR TEST_RUNNER_BROKEN: no runner\n",
				normalized(written.toString(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("read_lines rend une table de lignes numérotées ; une plage hors fichier lève")
	void readLinesFromLua(@TempDir final Path project) throws IOException {
		Files.writeString(project.resolve("f.txt"), "a\nb\nc\n");

		final String printed = run(project, """
				local r = read_lines("f.txt", 2, 9)
				print(r.path, r.lineCount, #r.lines.items, r.lines.items[1].line, r.lines.items[1].text)
				local ok, err = pcall(read_lines, "f.txt", 7, 8)
				print(ok, err:match("^%?ERROR [%u_]+"))
				""");

		assertEquals("f.txt\t3\t2\t2\tb\nfalse\t?ERROR LINE_OUT_OF_RANGE\n", normalized(printed));
	}

	@Test
	@DisplayName("snapshot puis changed_since rendent des tables, avec les md5 avant et après, d'un script à l'autre")
	void snapshotFromLua(@TempDir final Path project) throws IOException {
		Files.createDirectories(project.resolve("refs"));
		Files.writeString(project.resolve("refs/a.svg"), "old");

		// Un seul contexte pour les deux scripts, comme le daemon : le snapshot
		// vit dans le contexte, pas dans la connexion qui l'a pris.
		final PrintStream out = new PrintStream(written, true, StandardCharsets.UTF_8);
		final ClideContext context = new ClideContext(new FilesRepository(project, null), null,
				CommandRepository.commands);

		new LuaBridge(context, out).run("""
				local taken = snapshot("s", "refs/**.svg")
				print(taken.id, taken.fileCount, taken.replaced)
				print(#changed_since("s").changes.items)
				""");
		assertEquals("s\t1\tfalse\n0\n", normalized(written.toString(StandardCharsets.UTF_8)));

		Files.writeString(project.resolve("refs/a.svg"), "new");
		Files.writeString(project.resolve("refs/b.svg"), "born");
		written.reset();

		new LuaBridge(context, out).run("""
				for _, f in ipairs(changed_since("s").changes.items) do
				  print(f.path, f.type, f.md5Before ~= "", f.md5After ~= "")
				end
				""");
		assertEquals("refs/a.svg\tchanged\ttrue\ttrue\nrefs/b.svg\tcreated\tfalse\ttrue\n",
				normalized(written.toString(StandardCharsets.UTF_8)));
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
