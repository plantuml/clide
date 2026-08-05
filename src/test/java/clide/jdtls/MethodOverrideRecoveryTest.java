package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.core.Monomorphic;

/**
 * Tests de la partie purement textuelle/arborescente de MethodOverrideRecovery
 * - la détection de générique, l'arité, et la marche dans un arbre
 * documentSymbol fabriqué à la main. Ce qui parle réellement à jdtls
 * (find()/overridesJdtlsMisses()/collectDeclaredMethods(), qui ont besoin
 * d'un LspClient connecté) n'est pas couvert ici : comme pour JdtlsSession
 * lui-même, cette partie-là se valide en conditions réelles (voir TESTS.md),
 * pas par un test unitaire.
 */
class MethodOverrideRecoveryTest {

	@Test
	@DisplayName("isAbstractDeclaration() reconnaît un corps absent (';') ou le mot-clé abstract")
	void isAbstractDeclarationRecognizesNoBodyOrTheKeyword() {
		assertTrue(MethodOverrideRecovery.isAbstractDeclaration("void draw();"));
		assertTrue(MethodOverrideRecovery.isAbstractDeclaration("public abstract void draw() ;"));
		assertFalse(MethodOverrideRecovery.isAbstractDeclaration("void draw() {"));
	}

	@Test
	@DisplayName("declaresTypeParameters() distingue <T> ouvert par la méthode d'un simple type de retour générique")
	void declaresTypeParametersDistinguishesOwnFromReturnedGenerics() {
		assertTrue(MethodOverrideRecovery.declaresTypeParameters("<SHAPE extends UShape> void draw(SHAPE shape) {"));
		assertTrue(MethodOverrideRecovery
				.declaresTypeParameters("public <X extends UShape> void draw(X shape) {"));
		assertFalse(MethodOverrideRecovery.declaresTypeParameters("List<String> names() {"),
				"un type de retour générique n'est pas la méthode qui déclare son propre <T>");
		assertFalse(MethodOverrideRecovery.declaresTypeParameters(null));
	}

	@Test
	@DisplayName("isGenericType() lit le nom jdtls tel quel, sans deviner en l'absence de nom")
	void isGenericTypeReadsTheNameAsIs() {
		final Monomorphic generic = Monomorphic.mapBuilder().putString("name", "AbstractUGraphic<O>").build();
		assertTrue(MethodOverrideRecovery.isGenericType(generic));

		final Monomorphic plain = Monomorphic.mapBuilder().putString("name", "UGraphic").build();
		assertFalse(MethodOverrideRecovery.isGenericType(plain));

		assertFalse(MethodOverrideRecovery.isGenericType(Monomorphic.mapBuilder().build()));
	}

	@Test
	@DisplayName("arityAfterName() compte les paramètres, ignore les virgules dans les génériques")
	void arityAfterNameCountsParametersIgnoringGenericCommas() {
		assertEquals(0, MethodOverrideRecovery.arityAfterName("void draw() {", "draw"));
		assertEquals(1, MethodOverrideRecovery.arityAfterName("void draw(UShape shape) {", "draw"));
		assertEquals(2,
				MethodOverrideRecovery.arityAfterName("void copy(Map<String, List<Integer>> m, int x) {", "copy"),
				"la virgule dans Map<String, List<Integer>> ne doit pas compter comme un paramètre séparé");
		assertEquals(-1, MethodOverrideRecovery.arityAfterName("void other(int a) {", "draw"),
				"nom absent de la déclaration");
		assertEquals(-1, MethodOverrideRecovery.arityAfterName(null, "draw"));
	}

	@Test
	@DisplayName("arityOfParameterList() rend -1 quand la liste ne se ferme pas sur la ligne")
	void arityOfParameterListReturnsMinusOneWhenUnclosed() {
		assertEquals(-1, MethodOverrideRecovery.arityOfParameterList("void draw(UShape shape", 9));
	}

	@Test
	@DisplayName("coversLine()/enclosingTypeNode() trouvent le type le plus profond couvrant la ligne")
	void enclosingTypeNodeFindsTheDeepestCoveringType() {
		final Monomorphic outerClass = typeNode(5, "Outer", 0, 20);
		final Monomorphic innerInterface = typeNode(11, "Inner", 5, 10);
		final Monomorphic withInner = Monomorphic.mapBuilder() //
				.putNumber("kind", 5) //
				.putString("name", "Outer") //
				.put("range", rangeOf(0, 20)) //
				.putList("children", List.of(innerInterface)) //
				.build();

		// La ligne 7 est couverte par les deux, mais Inner est le plus profond.
		final Monomorphic found = MethodOverrideRecovery.enclosingTypeNode(List.of(withInner), 7);
		assertEquals("Inner", found.getOrNull("name").stringOrNull());

		// La ligne 15 n'est couverte que par Outer.
		final Monomorphic foundOuterOnly = MethodOverrideRecovery.enclosingTypeNode(List.of(withInner), 15);
		assertEquals("Outer", foundOuterOnly.getOrNull("name").stringOrNull());

		assertFalse(MethodOverrideRecovery.enclosingTypeNode(List.of(outerClass), 999).isMap(),
				"aucun type ne couvre une ligne hors de tous les ranges");
	}

	@Test
	@DisplayName("typeNodeAt() trouve le type dont le nom est déclaré exactement sur la ligne")
	void typeNodeAtFindsTheTypeDeclaredExactlyOnTheLine() {
		final Monomorphic method = Monomorphic.mapBuilder() //
				.putNumber("kind", 6) //
				.put("selectionRange", rangeOf(3, 3)) //
				.build();
		final Monomorphic type = Monomorphic.mapBuilder() //
				.putNumber("kind", 5) //
				.put("selectionRange", rangeOf(0, 0)) //
				.putList("children", List.of(method)) //
				.build();

		assertTrue(MethodOverrideRecovery.typeNodeAt(List.of(type), 0).isMap());
		assertFalse(MethodOverrideRecovery.typeNodeAt(List.of(type), 3).isMap(),
				"la ligne 3 est celle d'une méthode, pas d'un type - ne doit pas la confondre avec un type");
		assertFalse(MethodOverrideRecovery.typeNodeAt(List.of(type), 999).isMap());
	}

	@Test
	@DisplayName("locationKey() identifie une location par 'uri:ligne', pour la déduplication")
	void locationKeyIdentifiesByUriAndLine() {
		final Monomorphic location = Monomorphic.mapBuilder() //
				.putString("uri", "file:///A.java") //
				.put("range", rangeOf(41, 41)) //
				.build();

		assertEquals("file:///A.java:41", MethodOverrideRecovery.locationKey(location));
	}

	private static Monomorphic typeNode(final int kind, final String name, final int startLine, final int endLine) {
		return Monomorphic.mapBuilder() //
				.putNumber("kind", kind) //
				.putString("name", name) //
				.put("range", rangeOf(startLine, endLine)) //
				.build();
	}

	private static Monomorphic rangeOf(final int startLine, final int endLine) {
		return Monomorphic.mapBuilder() //
				.put("start", Monomorphic.mapBuilder().putNumber("line", startLine).build()) //
				.put("end", Monomorphic.mapBuilder().putNumber("line", endLine).build()) //
				.build();
	}

}
