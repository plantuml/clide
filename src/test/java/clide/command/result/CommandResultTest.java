package clide.command.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import clide.result.Listing;
import clide.result.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de CommandResult - l'enveloppe commune à toutes les commandes.
 *
 * Ce qui est vérifié tient surtout aux invariants que le constructeur compact
 * fait respecter : status et code ne peuvent pas se contredire, une erreur doit
 * dire quelque chose, et un avertissement n'empêche pas un résultat d'être OK.
 * C'est cette dernière promesse qui justifie que CommandStatus reste binaire.
 */
class CommandResultTest {

	@Test
	@DisplayName("un résultat OK porte ErrorCode.NONE et rien d'autre")
	void okCarriesNoCode() {
		final CommandResult result = CommandResult.ok(new CommandPayload.Text("bonjour"));

		assertEquals(CommandStatus.OK, result.status());
		assertEquals(ErrorCode.NONE, result.code());
		assertFalse(result.isError());
		assertEquals("", result.message());
		assertFalse(result.hasHint());
	}

	@Test
	@DisplayName("un OK ne peut pas porter un code d'erreur")
	void okCannotCarryACode() {
		assertThrows(IllegalArgumentException.class, () -> new CommandResult(CommandStatus.OK,
				ErrorCode.FILE_NOT_FOUND, "", "", List.of(), CommandPayload.NOTHING));
	}

	@Test
	@DisplayName("une erreur doit nommer une raison, NONE n'en est pas une")
	void errorMustNameAReason() {
		assertThrows(IllegalArgumentException.class, () -> new CommandResult(CommandStatus.ERROR, ErrorCode.NONE,
				"quelque chose a échoué", "", List.of(), CommandPayload.NOTHING));
	}

	@Test
	@DisplayName("une erreur muette est refusée")
	void errorMustSaySomething() {
		assertThrows(IllegalArgumentException.class,
				() -> CommandResult.error(ErrorCode.FILE_NOT_FOUND, ""));
	}

	@Test
	@DisplayName("un résultat OK peut porter des avertissements - c'est pourquoi le statut reste binaire")
	void warningsDoNotMakeAResultAnError() {
		final CommandResult result = CommandResult.empty()
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, "$refactor_foo"));

		assertEquals(CommandStatus.OK, result.status());
		assertFalse(result.isError());
		assertEquals(1, result.warnings().size());
		assertEquals(WarningCode.TRANSACTIONS_STILL_OPEN, result.warnings().get(0).code());
	}

	@Test
	@DisplayName("withWarning() rend une copie et laisse l'original intact")
	void withWarningIsACopy() {
		final CommandResult original = CommandResult.empty();
		final CommandResult derived = original
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, "$refactor_foo"));

		assertTrue(original.warnings().isEmpty());
		assertEquals(1, derived.warnings().size());
	}

	@Test
	@DisplayName("withWarnings() sans aucun avertissement rend l'objet lui-même")
	void withNoWarningChangesNothing() {
		final CommandResult original = CommandResult.empty();

		assertTrue(original == original.withWarnings(List.of()));
	}

	@Test
	@DisplayName("une erreur peut tout de même porter un payload - run_tests liste ses échecs")
	void anErrorCanStillCarryAPayload() {
		final CommandPayload payload = new CommandPayload.TestRun("demo.CalcTest", 9, 3, 0, 42,
				Listing.of(List.of(TestOutcome.passed("demo.CalcTest.addWorks")), 100), true);
		final CommandResult result = CommandResult.error(ErrorCode.TEST_FAILURES, "3 test(s) failed out of 12", "",
				payload);

		assertTrue(result.isError());
		assertEquals(payload, result.payload());
	}

	@Test
	@DisplayName("un avertissement sans message est refusé")
	void warningMustSaySomething() {
		assertThrows(IllegalArgumentException.class,
				() -> Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN, ""));
	}

}
