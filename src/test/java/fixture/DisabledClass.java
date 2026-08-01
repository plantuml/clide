package fixture;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Classe désactivée en entier : JUnit ne signale que le conteneur, jamais le
 * test dessous. Sans parcourir les descendants, ce test disparaît des
 * compteurs et la classe rapporte une arithmétique impossible.
 */
@Disabled("cobaye : desactivee en entier")
public class DisabledClass {

	@Test
	void neverRuns() {
	}
}
