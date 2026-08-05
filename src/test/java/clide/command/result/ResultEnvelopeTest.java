package clide.command.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de ResultEnvelope - la seule mise en forme partagée par toutes les
 * commandes, donc la seule que les clients (humain ou programme) peuvent
 * reconnaître sans rien savoir de la commande qui a répondu.
 *
 * Chaque texte attendu est écrit à la main ici, caractère par caractère : c'est
 * un contrat de protocole, et le figer explicitement est exactement le point.
 */
class ResultEnvelopeTest {

	@Test
	@DisplayName("un succès n'a pas d'en-tête du tout - le corps est la réponse")
	void successHasNoHeader() {
		final CommandResult result = CommandResult.ok(new CommandPayload.Text("peu importe"));

		assertEquals("find_symbol: 2 symbol(s)", ResultEnvelope.render(result, "find_symbol: 2 symbol(s)"));
	}

	@Test
	@DisplayName("un succès sans rien à dire n'écrit rien")
	void silentSuccessIsSilent() {
		assertEquals("", ResultEnvelope.render(CommandResult.empty(), ""));
	}

	@Test
	@DisplayName("une erreur nomme son code puis son message")
	void errorNamesItsCode() {
		final CommandResult result = CommandResult.error(ErrorCode.LINE_OUT_OF_RANGE,
				"Line 999 out of range (file has 312 line(s)): Foo.java");

		assertEquals("?ERROR LINE_OUT_OF_RANGE: Line 999 out of range (file has 312 line(s)): Foo.java",
				ResultEnvelope.render(result, ""));
	}

	@Test
	@DisplayName("le hint apparaît sur sa propre ligne, sous l'erreur")
	void hintGoesOnItsOwnLine() {
		final CommandResult result = CommandResult.error(ErrorCode.NO_TEST_FOUND, "no test found in demo.CalcTest",
				"run rebuild first");

		assertEquals("?ERROR NO_TEST_FOUND: no test found in demo.CalcTest\nhint: run rebuild first",
				ResultEnvelope.render(result, ""));
	}

	@Test
	@DisplayName("une erreur qui a tout de même quelque chose à montrer le montre après l'en-tête")
	void errorBodyComesAfterTheHeader() {
		final CommandResult result = CommandResult.error(ErrorCode.TEST_FAILURES, "3 test(s) failed out of 12", "",
				CommandPayload.NOTHING);

		assertEquals("?ERROR TEST_FAILURES: 3 test(s) failed out of 12\nrun_tests: 12 test(s), 9 passed, 3 failed",
				ResultEnvelope.render(result, "run_tests: 12 test(s), 9 passed, 3 failed"));
	}

	@Test
	@DisplayName("les avertissements suivent la réponse, un par ligne, avec leur code")
	void warningsFollowTheAnswer() {
		final CommandResult result = CommandResult.empty()
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, "$refactor_foo"))
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, "$refactor_bar"));

		assertEquals("find_reference: 1 location(s)\n"
				+ "!WARNING TRANSACTIONS_STILL_OPEN: $refactor_foo\n"
				+ "!WARNING TRANSACTIONS_STILL_OPEN: $refactor_bar",
				ResultEnvelope.render(result, "find_reference: 1 location(s)"));
	}

	@Test
	@DisplayName("un avertissement seul s'écrit sans ligne vide devant")
	void warningAloneHasNoLeadingBlank() {
		final CommandResult result = CommandResult.empty()
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, "$refactor_foo"));

		assertEquals("!WARNING TRANSACTIONS_STILL_OPEN: $refactor_foo", ResultEnvelope.render(result, ""));
	}

	@Test
	@DisplayName("defaultBody() rend le texte tel quel et rien pour Nothing")
	void defaultBodyHandlesTheTwoNeutralPayloads() {
		assertEquals("", ResultEnvelope.defaultBody(CommandPayload.NOTHING));
		assertEquals("du markdown jdtls", ResultEnvelope.defaultBody(new CommandPayload.Text("du markdown jdtls")));
	}

}
