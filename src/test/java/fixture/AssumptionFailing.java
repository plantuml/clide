package fixture;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Un test dont l'hypothèse ({@code Assumptions.assumeTrue}) échoue avant
 * d'atteindre le corps du test - le cas d'un test qui dépend d'une lib
 * optionnelle absente (ELK côté PlantUML, typiquement). JUnit le rapporte en
 * ABORTED, pas en FAILED : c'est ce que TestRunnerMain doit distinguer.
 */
public class AssumptionFailing {

	@Test
	void skippedBecauseAssumptionFails() {
		Assumptions.assumeTrue(false, "cobaye : hypothese jamais vraie");
	}

	@Test
	void passesNormally() {
	}
}
