package fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Le cas qui a cassé, réduit : aucun @Test nu, cinq invocations paramétrées.
 * Au moment de testPlanExecutionStarted, JUnit n'a encore enregistré aucune
 * d'elles - la méthode est un CONTENEUR, ses invocations arrivent
 * dynamiquement à l'exécution.
 */
public class ParameterizedOnly {

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3, 4, 5 })
	void everyValueIsPositive(final int value) {
		assertTrue(value > 0);
	}
}
