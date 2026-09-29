package fixture;

import java.util.List;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Le cas de VegaTest de PlantUML : une @TestFactory qui range ses tests dans un
 * conteneur par dossier, et deux dossiers qui contiennent chacun un test du même
 * nom. Seul le conteneur distingue les deux, et il n'est pas dans l'identifiant
 * unique de JUnit (qui ne porte que des numéros d'ordre).
 *
 * Celui du dossier "second" échoue exprès, pour qu'un enregistrement FAIL
 * existe et que l'on vérifie lequel des deux l'a produit.
 */
public class DynamicContainers {

	@TestFactory
	List<DynamicNode> sameNameInTwoFolders() {
		return List.of(
				DynamicContainer.dynamicContainer("first",
						List.of(DynamicTest.dynamicTest("case.puml", () -> {
						}))),
				DynamicContainer.dynamicContainer("second", List.of(DynamicContainer.dynamicContainer("nested",
						List.of(DynamicTest.dynamicTest("case.puml", () -> fail("cobaye : echec voulu")))))));
	}

	@TestFactory
	List<DynamicTest> withoutAnyContainer() {
		return List.of(DynamicTest.dynamicTest("bare.puml", () -> {
		}));
	}
}
