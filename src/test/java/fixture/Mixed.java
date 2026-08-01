package fixture;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deux @Test nus, trois invocations paramétrées, quatre répétitions : neuf
 * tests. Le cas silencieux - l'ancien comptage annonçait « 2 test(s), 9
 * passed » sans que rien ne proteste.
 */
public class Mixed {

	@Test
	void plainOne() {
	}

	@Test
	void plainTwo() {
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3 })
	void parameterized(final int value) {
	}

	@RepeatedTest(4)
	void repeated() {
	}
}
