package fixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Une méthode dans une classe @Nested - le second cas que TestSelector ne
 * pouvait pas nommer avant ce correctif (voir TestRunnerMainExecutionTest).
 */
public class NestedFixture {

	@Test
	void outerPasses() {
		assertTrue(true);
	}

	@Nested
	class InnerGroup {

		@Test
		void innerPasses() {
			assertTrue(true);
		}

		@Test
		void innerPassesToo() {
			assertTrue(true);
		}
	}

}
