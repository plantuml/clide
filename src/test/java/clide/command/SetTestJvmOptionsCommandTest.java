package clide.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.CommandStatus;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.FilesRepository;

/**
 * Tests de set_test_jvm_options : le découpage de la ligne, et ce que la commande
 * répond - l'effet sur le contexte est couvert par TestSettingsTest.
 */
class SetTestJvmOptionsCommandTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	@Test
	@DisplayName("la ligne est découpée sur les blancs, quel qu'en soit le nombre")
	void splitsOnWhitespace() {
		assertEquals(List.of("-Xmx256m", "-XX:+UseSerialGC", "-Xlog:gc"),
				SetTestJvmOptionsCommand.tokenize("  -Xmx256m \t -XX:+UseSerialGC   -Xlog:gc "));
	}

	@Test
	@DisplayName("un passage entre guillemets est un seul jeton, guillemets retirés")
	void quotedStretchIsOneToken() {
		assertEquals(List.of("-Xlog:gc:file=my logs/gc.txt", "-Xint"),
				SetTestJvmOptionsCommand.tokenize("-Xlog:gc:file=\"my logs/gc.txt\" -Xint"));
	}

	@Test
	@DisplayName("une ligne vide ou blanche ne donne aucun jeton")
	void blankLineHasNoToken() {
		assertEquals(List.of(), SetTestJvmOptionsCommand.tokenize(""));
		assertEquals(List.of(), SetTestJvmOptionsCommand.tokenize("   \t "));
	}

	@Test
	@DisplayName("des guillemets vides donnent un jeton vide, que le contexte refusera")
	void emptyQuotesAreAnEmptyToken() {
		assertEquals(List.of(""), SetTestJvmOptionsCommand.tokenize("\"\""));
	}

	@Test
	@DisplayName("un guillemet jamais fermé est refusé en le nommant")
	void unbalancedQuoteIsRefused() {
		final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> SetTestJvmOptionsCommand.tokenize("-Dx=\"a b"));

		assertEquals("unbalanced double quote in '-Dx=\"a b'", refused.getMessage());
	}

	@Test
	@DisplayName("la réponse donne l'ancienne valeur puis la nouvelle")
	void answersWithPreviousAndNew(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		final SetTestJvmOptionsCommand command = new SetTestJvmOptionsCommand();

		final CommandResult first = command.executeCommand(context, "-Xmx64m -Xint");
		final CommandResult second = command.executeCommand(context, "-XX:+UseSerialGC");

		assertEquals("set_test_jvm_options: test_jvm_options '' -> '-Xmx64m -Xint'",
				command.render(first, PrintMode.AI));
		assertEquals("set_test_jvm_options: test_jvm_options '-Xmx64m -Xint' -> '-XX:+UseSerialGC'",
				command.render(second, PrintMode.AI));
		assertEquals(List.of("-XX:+UseSerialGC"), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("une ligne blanche efface les options")
	void blankClears(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		final SetTestJvmOptionsCommand command = new SetTestJvmOptionsCommand();
		command.executeCommand(context, "-Xint");

		final CommandResult cleared = command.executeCommand(context, "   ");

		assertEquals(CommandStatus.OK, cleared.status());
		assertEquals(new CommandPayload.Setting("test_jvm_options", "-Xint", ""), cleared.payload());
		assertEquals(List.of(), context.getTestJvmOptions());
	}

	@Test
	@DisplayName("une option refusée est une erreur de valeur, et les options restent celles d'avant")
	void refusalIsAnErrorAndChangesNothing(@TempDir final Path root) {
		final ClideContext context = contextOn(root);
		final SetTestJvmOptionsCommand command = new SetTestJvmOptionsCommand();
		command.executeCommand(context, "-Xint");

		final CommandResult refused = command.executeCommand(context, "-Xmx64m -cp other.jar");

		assertEquals(CommandStatus.ERROR, refused.status());
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, refused.code());
		assertEquals(List.of("-Xint"), context.getTestJvmOptions());
	}

}
