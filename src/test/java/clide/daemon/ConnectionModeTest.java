package clide.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.PrintMode;

/**
 * Tests de ConnectionMode - la première ligne d'une connexion, et rien d'autre,
 * décide de la façon dont le daemon la sert.
 *
 * L'enjeu réel de ces tests n'est pas la reconnaissance des deux drapeaux, elle
 * est triviale : c'est le cas AI, où la ligne lue n'est *pas* un handshake mais
 * déjà une commande. La confondre avec un préambule la ferait disparaître
 * silencieusement, et une session pipée perdrait sa première commande sans
 * qu'aucune erreur ne le dise.
 */
class ConnectionModeTest {

	@Test
	@DisplayName("--human annonce le mode humain")
	void humanFlagIsRecognized() {
		assertEquals(ConnectionMode.HUMAN, ConnectionMode.of(PrintMode.HUMAN_FLAG));
	}

	@Test
	@DisplayName("--lua annonce un script")
	void scriptFlagIsRecognized() {
		assertEquals(ConnectionMode.SCRIPT, ConnectionMode.of(ConnectionMode.SCRIPT_FLAG));
	}

	@Test
	@DisplayName("une ligne qui n'est aucun des deux drapeaux n'est pas un handshake")
	void anythingElseIsACommand() {
		assertEquals(ConnectionMode.AI, ConnectionMode.of("find_symbol"));
		assertEquals(ConnectionMode.AI, ConnectionMode.of(""));
		assertEquals(ConnectionMode.AI, ConnectionMode.of("--lua extra"));
	}

	@Test
	@DisplayName("le drapeau est reconnu même entouré d'espaces")
	void flagIsTrimmed() {
		assertEquals(ConnectionMode.SCRIPT, ConnectionMode.of("  --lua  "));
	}

	@Test
	@DisplayName("seul le mode AI n'a consommé aucune ligne")
	void onlyAiConsumedNothing() {
		assertFalse(ConnectionMode.AI.announced());
		assertTrue(ConnectionMode.HUMAN.announced());
		assertTrue(ConnectionMode.SCRIPT.announced());
	}

	@Test
	@DisplayName("un script n'imprime pas d'invite : son mode d'écriture est celui d'une session AI")
	void scriptPrintsLikeAi() {
		assertEquals(PrintMode.AI, ConnectionMode.SCRIPT.printMode());
		assertEquals(PrintMode.AI, ConnectionMode.AI.printMode());
		assertEquals(PrintMode.HUMAN, ConnectionMode.HUMAN.printMode());
	}

}
