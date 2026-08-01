package fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La pire forme du bug : une classe paramétrée dont un cas échoue. Elle était
 * rapportée « no test found », l'échec compris - une régression réelle cachée
 * derrière un message qui ressemble à un problème de configuration.
 */
public class ParameterizedFailing {

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, -3, 4 })
	void everyValueIsPositive(final int value) {
		assertTrue(value > 0, "valeur negative : " + value);
	}
}
