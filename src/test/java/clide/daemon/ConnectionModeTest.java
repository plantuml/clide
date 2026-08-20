package clide.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de ConnectionMode - la première ligne d'une connexion, et rien d'autre,
 * décide si elle porte un script ou un flux de commandes.
 *
 * L'enjeu réel de ces tests n'est pas la reconnaissance du drapeau, elle est
 * triviale : c'est le cas COMMANDS, où la ligne lue n'est *pas* un handshake
 * mais déjà une commande. La confondre avec un préambule la ferait disparaître
 * silencieusement, et une session pipée perdrait sa première commande sans
 * qu'aucune erreur ne le dise.
 */
class ConnectionModeTest {

	@Test
	@DisplayName("--lua annonce un script")
	void scriptFlagIsRecognized() {
		assertEquals(ConnectionMode.SCRIPT, ConnectionMode.of(ConnectionMode.SCRIPT_FLAG));
	}

	@Test
	@DisplayName("une ligne qui n'est pas le drapeau n'est pas un handshake")
	void anythingElseIsACommand() {
		assertEquals(ConnectionMode.COMMANDS, ConnectionMode.of("find_symbol"));
		assertEquals(ConnectionMode.COMMANDS, ConnectionMode.of(""));
		assertEquals(ConnectionMode.COMMANDS, ConnectionMode.of("--lua extra"));
		assertEquals(ConnectionMode.COMMANDS, ConnectionMode.of("--human"));
	}

	@Test
	@DisplayName("le drapeau est reconnu même entouré d'espaces")
	void flagIsTrimmed() {
		assertEquals(ConnectionMode.SCRIPT, ConnectionMode.of("  --lua  "));
	}

	@Test
	@DisplayName("seul le mode COMMANDS n'a consommé aucune ligne")
	void onlyCommandsConsumedNothing() {
		assertFalse(ConnectionMode.COMMANDS.announced());
		assertTrue(ConnectionMode.SCRIPT.announced());
	}

}
