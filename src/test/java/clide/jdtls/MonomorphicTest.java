package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests de Monomorphic, la valeur JSON unique appelée à remplacer Truc et les
 * Object de Json.
 *
 * Deux propriétés comptent plus que les autres et structurent ce fichier :
 *
 * - lire une valeur comme un type qu'elle n'a pas doit lever une exception qui
 *   nomme les deux types. C'est la leçon du refactoring Truc, où getString()
 *   appliqué à une Map partait en ClassCastException nu quand il ne renvoyait
 *   pas un null qui voyageait jusqu'à un plantage sans rapport ;
 * - une valeur est immuable, y compris vis-à-vis de la collection dont elle a
 *   été construite. LspClient parse sur son thread lecteur et publie vers les
 *   threads appelants : une fuite de référence mutable serait une course.
 */
class MonomorphicTest {

	@Nested
	@DisplayName("NULL")
	class NullValue {

		@Test
		@DisplayName("createNull() porte le type NULL et rien d'autre")
		void hasTypeNull() {
			final Monomorphic value = Monomorphic.createNull();

			assertEquals(MonomorphicType.NULL, value.getType());
			assertTrue(value.isNull());
			assertFalse(value.isString());
			assertFalse(value.isNumber());
			assertFalse(value.isBoolean());
			assertFalse(value.isList());
			assertFalse(value.isMap());
		}

		@Test
		@DisplayName("tous les NULL sont égaux")
		void allNullsAreEqual() {
			assertEquals(Monomorphic.createNull(), Monomorphic.createNull());
			assertEquals(Monomorphic.createNull().hashCode(), Monomorphic.createNull().hashCode());
		}
	}

	@Nested
	@DisplayName("STRING")
	class StringValue {

		@Test
		@DisplayName("createString() rend la chaîne telle quelle")
		void roundTrips() {
			final Monomorphic value = Monomorphic.createString("file:///tmp/A.java");

			assertEquals(MonomorphicType.STRING, value.getType());
			assertTrue(value.isString());
			assertEquals("file:///tmp/A.java", value.asString());
		}

		@Test
		@DisplayName("la chaîne vide est une STRING, pas un NULL")
		void emptyStringIsAString() {
			final Monomorphic value = Monomorphic.createString("");

			assertTrue(value.isString());
			assertFalse(value.isNull());
			assertEquals("", value.asString());
		}

		@Test
		@DisplayName("createString(null) est refusé - c'est createNull() qu'il faut")
		void rejectsJavaNull() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Monomorphic.createString(null));

			assertTrue(thrown.getMessage().contains("createNull"), thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("BOOLEAN")
	class BooleanValue {

		@Test
		@DisplayName("createBoolean() rend le booléen tel quel")
		void roundTrips() {
			assertTrue(Monomorphic.createBoolean(true).asBoolean());
			assertFalse(Monomorphic.createBoolean(false).asBoolean());
			assertEquals(MonomorphicType.BOOLEAN, Monomorphic.createBoolean(true).getType());
		}

		@Test
		@DisplayName("false n'est pas NULL - le piège classique du modèle non typé")
		void falseIsNotNull() {
			final Monomorphic value = Monomorphic.createBoolean(false);

			assertTrue(value.isBoolean());
			assertFalse(value.isNull());
		}

		@Test
		@DisplayName("true et false ne sont pas égaux")
		void trueDiffersFromFalse() {
			assertNotEquals(Monomorphic.createBoolean(true), Monomorphic.createBoolean(false));
		}
	}

	@Nested
	@DisplayName("NUMBER")
	class NumberValue {

		@Test
		@DisplayName("un entier reste un entier exact")
		void integerRoundTrips() {
			final Monomorphic value = Monomorphic.createNumber(41L);

			assertEquals(MonomorphicType.NUMBER, value.getType());
			assertTrue(value.isIntegral());
			assertEquals(41L, value.asLong());
			assertEquals(41, value.asInt());
			assertEquals(41.0, value.asDouble());
		}

		@Test
		@DisplayName("un long que double ne peut pas représenter survit intact")
		void largeLongKeepsEveryBit() {
			// 2^53 + 1 : le premier entier que double ne sait plus distinguer de
			// son voisin. C'est ce cas qui condamnait le champ "int number", et
			// qui condamnerait aussi un NUMBER stocké en double : l'id JSON-RPC
			// sort d'un AtomicLong et sert à apparier requête et réponse.
			final long id = (1L << 53) + 1;

			assertEquals(id, Monomorphic.createNumber(id).asLong());
			assertEquals(Long.MAX_VALUE, Monomorphic.createNumber(Long.MAX_VALUE).asLong());
			assertEquals(Long.MIN_VALUE, Monomorphic.createNumber(Long.MIN_VALUE).asLong());
		}

		@Test
		@DisplayName("un décimal reste un décimal")
		void decimalRoundTrips() {
			final Monomorphic value = Monomorphic.createNumber(1.5);

			assertFalse(value.isIntegral());
			assertEquals(1.5, value.asDouble());
		}

		@Test
		@DisplayName("un décimal qui vaut un entier se lit comme un long")
		void wholeDecimalReadsAsLong() {
			// jdtls a le droit d'écrire 41.0 là où 41 était attendu ; refuser
			// serait rigide, tronquer serait faux.
			assertEquals(41L, Monomorphic.createNumber(41.0).asLong());
			assertEquals(41, Monomorphic.createNumber(41.0).asInt());
		}

		@Test
		@DisplayName("un décimal à vraie partie fractionnaire refuse d'être tronqué")
		void fractionalDecimalRefusesToBecomeLong() {
			final IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> Monomorphic.createNumber(1.5).asLong());

			assertTrue(thrown.getMessage().contains("1.5"), thrown.getMessage());
		}

		@Test
		@DisplayName("NaN et l'infini ne deviennent pas des long")
		void nonFiniteRefusesToBecomeLong() {
			assertThrows(IllegalStateException.class, () -> Monomorphic.createNumber(Double.NaN).asLong());
			assertThrows(IllegalStateException.class, () -> Monomorphic.createNumber(Double.POSITIVE_INFINITY).asLong());
		}

		@Test
		@DisplayName("asInt() échoue au lieu de repasser par zéro")
		void asIntDoesNotWrapAround() {
			final Monomorphic tooBig = Monomorphic.createNumber(Integer.MAX_VALUE + 1L);

			final IllegalStateException thrown = assertThrows(IllegalStateException.class, tooBig::asInt);
			assertTrue(thrown.getMessage().contains("int"), thrown.getMessage());
		}

		@Test
		@DisplayName("1 et 1.0 ne sont pas égaux : ils ne s'écrivent pas pareil")
		void integerDiffersFromDecimal() {
			// Toute la raison d'être du drapeau integral. Les confondre ferait
			// sortir "line":41 en "line":41.0 sur le fil.
			assertNotEquals(Monomorphic.createNumber(1L), Monomorphic.createNumber(1.0));
			assertEquals("1", Monomorphic.createNumber(1L).toString());
			assertEquals("1.0", Monomorphic.createNumber(1.0).toString());
		}

		@Test
		@DisplayName("deux nombres identiques sont égaux")
		void sameNumbersAreEqual() {
			assertEquals(Monomorphic.createNumber(7L), Monomorphic.createNumber(7L));
			assertEquals(Monomorphic.createNumber(7L).hashCode(), Monomorphic.createNumber(7L).hashCode());
			assertEquals(Monomorphic.createNumber(2.5), Monomorphic.createNumber(2.5));
		}
	}

	@Nested
	@DisplayName("LIST")
	class ListValue {

		@Test
		@DisplayName("createList() garde les éléments dans l'ordre")
		void keepsOrder() {
			final Monomorphic value = Monomorphic.createList(Monomorphic.createString("a"),
					Monomorphic.createNumber(2L));

			assertEquals(MonomorphicType.LIST, value.getType());
			assertEquals(2, value.size());
			assertEquals("a", value.get(0).asString());
			assertEquals(2L, value.get(1).asLong());
		}

		@Test
		@DisplayName("une liste vide est une LIST")
		void emptyListIsAList() {
			final Monomorphic value = Monomorphic.createList(List.of());

			assertTrue(value.isList());
			assertEquals(0, value.size());
		}

		@Test
		@DisplayName("modifier la liste source après coup ne change rien")
		void copiesTheSourceList() {
			final List<Monomorphic> source = new ArrayList<>();
			source.add(Monomorphic.createString("a"));
			final Monomorphic value = Monomorphic.createList(source);

			source.add(Monomorphic.createString("b"));

			assertEquals(1, value.size());
		}

		@Test
		@DisplayName("asList() ne se laisse pas modifier")
		void exposesAnUnmodifiableList() {
			final Monomorphic value = Monomorphic.createList(Monomorphic.createString("a"));

			assertThrows(UnsupportedOperationException.class, () -> value.asList().add(Monomorphic.createNull()));
		}

		@Test
		@DisplayName("un élément null est refusé, et le message dit lequel")
		void rejectsNullElement() {
			final List<Monomorphic> source = new ArrayList<>();
			source.add(Monomorphic.createString("a"));
			source.add(null);

			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Monomorphic.createList(source));
			assertTrue(thrown.getMessage().contains("index 1"), thrown.getMessage());
		}

		@Test
		@DisplayName("un index hors bornes dit combien il y avait d'éléments")
		void indexOutOfRangeIsExplicit() {
			final Monomorphic value = Monomorphic.createList(Monomorphic.createString("a"));

			assertTrue(assertThrows(IllegalArgumentException.class, () -> value.get(1)).getMessage().contains("1 element"));
			assertThrows(IllegalArgumentException.class, () -> value.get(-1));
		}

		@Test
		@DisplayName("deux listes de mêmes éléments sont égales, l'ordre compte")
		void equalityIsElementwiseAndOrdered() {
			final Monomorphic ab = Monomorphic.createList(Monomorphic.createString("a"), Monomorphic.createString("b"));
			final Monomorphic ba = Monomorphic.createList(Monomorphic.createString("b"), Monomorphic.createString("a"));

			assertEquals(ab, Monomorphic.createList(Monomorphic.createString("a"), Monomorphic.createString("b")));
			assertNotEquals(ab, ba);
		}
	}

	@Nested
	@DisplayName("MAP")
	class MapValue {

		@Test
		@DisplayName("createMap() garde les entrées et leur ordre d'insertion")
		void keepsEntriesAndOrder() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("uri", "file:///A.java").putNumber("line", 41L)
					.build();

			assertEquals(MonomorphicType.MAP, value.getType());
			assertEquals(2, value.size());
			assertEquals(List.of("uri", "line"), List.copyOf(value.asMap().keySet()));
		}

		@Test
		@DisplayName("modifier la map source après coup ne change rien")
		void copiesTheSourceMap() {
			final Map<String, Monomorphic> source = new LinkedHashMap<>();
			source.put("a", Monomorphic.createString("1"));
			final Monomorphic value = Monomorphic.createMap(source);

			source.put("b", Monomorphic.createString("2"));

			assertEquals(1, value.size());
			assertFalse(value.containsKey("b"));
		}

		@Test
		@DisplayName("asMap() ne se laisse pas modifier")
		void exposesAnUnmodifiableMap() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("a", "1").build();

			assertThrows(UnsupportedOperationException.class, () -> value.asMap().put("b", Monomorphic.createNull()));
		}

		@Test
		@DisplayName("containsKey() distingue la clé absente de la clé à null")
		void tellsAbsentFromExplicitlyNull() {
			// La distinction n'est pas théorique : JdtlsSession teste
			// containsKey("error") pour savoir si la réponse est une erreur.
			final Monomorphic value = Monomorphic.mapBuilder().putNull("error").build();

			assertTrue(value.containsKey("error"));
			assertTrue(value.get("error").isNull());
			assertFalse(value.containsKey("result"));
		}

		@Test
		@DisplayName("get() sur une clé absente échoue et liste les clés connues")
		void missingKeyIsLoudAndHelpful() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("uri", "x").putString("name", "y").build();

			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> value.get("range"));
			assertTrue(thrown.getMessage().contains("range"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("uri"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("name"), thrown.getMessage());
		}

		@Test
		@DisplayName("getOrDefault() rend le défaut sans lever")
		void getOrDefaultFallsBack() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("uri", "x").build();
			final Monomorphic fallback = Monomorphic.createList();

			assertEquals("x", value.getOrDefault("uri", fallback).asString());
			assertSame(fallback, value.getOrDefault("absent", fallback));
		}

		@Test
		@DisplayName("une valeur null est refusée, et le message nomme la clé")
		void rejectsNullValue() {
			final Map<String, Monomorphic> source = new LinkedHashMap<>();
			source.put("uri", null);

			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Monomorphic.createMap(source));
			assertTrue(thrown.getMessage().contains("uri"), thrown.getMessage());
		}

		@Test
		@DisplayName("l'ordre des clés ne change pas l'égalité")
		void keyOrderDoesNotAffectEquality() {
			final Monomorphic ab = Monomorphic.mapBuilder().putString("a", "1").putString("b", "2").build();
			final Monomorphic ba = Monomorphic.mapBuilder().putString("b", "2").putString("a", "1").build();

			assertEquals(ab, ba);
			assertEquals(ab.hashCode(), ba.hashCode());
		}
	}

	@Nested
	@DisplayName("Builder")
	class BuilderBehaviour {

		@Test
		@DisplayName("chaque putXxx() pose le bon type")
		void eachPutSetsItsType() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("s", "x").putBoolean("b", true)
					.putNumber("i", 7L).putNumber("d", 1.5).putNull("n")
					.putList("l", List.of(Monomorphic.createNumber(1L))).build();

			assertEquals(MonomorphicType.STRING, value.get("s").getType());
			assertEquals(MonomorphicType.BOOLEAN, value.get("b").getType());
			assertTrue(value.get("i").isIntegral());
			assertFalse(value.get("d").isIntegral());
			assertEquals(MonomorphicType.NULL, value.get("n").getType());
			assertEquals(MonomorphicType.LIST, value.get("l").getType());
		}

		@Test
		@DisplayName("une clé répétée remplace la précédente sans changer sa place")
		void repeatedKeyReplaces() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("a", "1").putString("b", "2")
					.putString("a", "3").build();

			assertEquals(2, value.size());
			assertEquals("3", value.get("a").asString());
			assertEquals(List.of("a", "b"), List.copyOf(value.asMap().keySet()));
		}

		@Test
		@DisplayName("continuer à alimenter le builder ne touche pas ce qu'il a déjà produit")
		void builtValuesAreSnapshots() {
			final Monomorphic.Builder builder = Monomorphic.mapBuilder().putString("a", "1");
			final Monomorphic first = builder.build();

			builder.putString("b", "2");
			final Monomorphic second = builder.build();

			assertEquals(1, first.size());
			assertEquals(2, second.size());
		}

		@Test
		@DisplayName("put(clé, null) est refusé - putNull() existe pour ça")
		void rejectsNullValue() {
			final Monomorphic.Builder builder = Monomorphic.mapBuilder();

			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> builder.put("a", null));
			assertTrue(thrown.getMessage().contains("putNull"), thrown.getMessage());
		}

		@Test
		@DisplayName("un builder vide produit une MAP vide, pas un NULL")
		void emptyBuilderBuildsAnEmptyMap() {
			final Monomorphic value = Monomorphic.mapBuilder().build();

			assertTrue(value.isMap());
			assertEquals(0, value.size());
		}
	}

	@Nested
	@DisplayName("Lire une valeur comme un type qu'elle n'a pas")
	class WrongTypeAccess {

		@Test
		@DisplayName("le message nomme le type attendu et le type réel")
		void namesBothTypes() {
			final Monomorphic number = Monomorphic.createNumber(41L);

			final IllegalStateException thrown = assertThrows(IllegalStateException.class, number::asString);
			assertEquals("Expected STRING but was NUMBER", thrown.getMessage());
		}

		@Test
		@DisplayName("aucun accesseur ne rend un null ou un ClassCastException à la place")
		void everyAccessorRefusesTheWrongType() {
			// Le scénario exact du refactoring Truc : getString() sur une Map
			// partait en ClassCastException nu, getObject() rendait un null qui
			// voyageait. Ici chaque combinaison échoue tôt et s'explique.
			final Monomorphic map = Monomorphic.mapBuilder().putString("a", "1").build();
			final Monomorphic list = Monomorphic.createList(Monomorphic.createNull());
			final Monomorphic string = Monomorphic.createString("x");
			final Monomorphic nul = Monomorphic.createNull();

			assertThrows(IllegalStateException.class, map::asString);
			assertThrows(IllegalStateException.class, map::asLong);
			assertThrows(IllegalStateException.class, map::asList);
			assertThrows(IllegalStateException.class, map::asBoolean);

			assertThrows(IllegalStateException.class, list::asMap);
			assertThrows(IllegalStateException.class, () -> list.get("a"));
			assertThrows(IllegalStateException.class, () -> list.containsKey("a"));

			assertThrows(IllegalStateException.class, string::asDouble);
			assertThrows(IllegalStateException.class, string::isIntegral);
			assertThrows(IllegalStateException.class, () -> string.get(0));

			assertThrows(IllegalStateException.class, nul::asString);
			assertThrows(IllegalStateException.class, nul::size);
		}

		@Test
		@DisplayName("size() accepte LIST et MAP, et le dit quand ce n'est ni l'un ni l'autre")
		void sizeAcceptsBothContainers() {
			assertEquals(1, Monomorphic.createList(Monomorphic.createNull()).size());
			assertEquals(1, Monomorphic.mapBuilder().putNull("a").build().size());

			final IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> Monomorphic.createString("x").size());
			assertEquals("Expected LIST or MAP but was STRING", thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("Égalité et rendu")
	class ValueSemantics {

		@Test
		@DisplayName("deux types différents ne sont jamais égaux")
		void differentTypesNeverMatch() {
			assertNotEquals(Monomorphic.createString("1"), Monomorphic.createNumber(1L));
			assertNotEquals(Monomorphic.createNull(), Monomorphic.createString(""));
			assertNotEquals(Monomorphic.createBoolean(false), Monomorphic.createNull());
			assertNotEquals(Monomorphic.createList(), Monomorphic.mapBuilder().build());
		}

		@Test
		@DisplayName("equals() supporte null et un autre type sans lever")
		void equalsIsDefensive() {
			final Monomorphic value = Monomorphic.createString("x");

			assertNotEquals(null, value);
			assertNotEquals("x", value);
			assertEquals(value, value);
		}

		@Test
		@DisplayName("une arborescence complète se compare d'un seul assertEquals")
		void wholeTreesCompare() {
			// C'est ce que ça achète concrètement : un test de Json pourra
			// s'écrire assertEquals(attendu, Json.parse(texte)) plutôt que de
			// descendre champ par champ.
			assertEquals(position(41, 8), position(41, 8));
			assertNotEquals(position(41, 8), position(41, 9));
		}

		@Test
		@DisplayName("toString() rend une forme JSON lisible dans un échec de test")
		void toStringIsReadable() {
			assertEquals("{\"line\":41,\"character\":8}", position(41, 8).toString());
			assertEquals("[\"a\",null,true]", Monomorphic
					.createList(Monomorphic.createString("a"), Monomorphic.createNull(), Monomorphic.createBoolean(true))
					.toString());
		}

		private Monomorphic position(final long line, final long character) {
			return Monomorphic.mapBuilder().putNumber("line", line).putNumber("character", character).build();
		}
	}

	@Nested
	@DisplayName("Imbrication")
	class Nesting {

		@Test
		@DisplayName("une réponse LSP réaliste se navigue sans un seul cast")
		void navigatesARealisticResponse() {
			// La forme que renvoie textDocument/definition, celle sur laquelle
			// JdtlsSession passait son temps à faire des instanceof Map.
			final Monomorphic range = Monomorphic.mapBuilder().put("start", position(6, 13))
					.put("end", position(6, 17)).build();
			final Monomorphic location = Monomorphic.mapBuilder().putString("uri", "file:///Truc.java")
					.put("range", range).build();
			final Monomorphic response = Monomorphic.mapBuilder().putString("jsonrpc", "2.0").putNumber("id", 1L)
					.putList("result", List.of(location)).build();

			assertEquals("file:///Truc.java", response.get("result").get(0).get("uri").asString());
			assertEquals(6, response.get("result").get(0).get("range").get("start").get("line").asInt());
			assertFalse(response.containsKey("error"));
		}

		@Test
		@DisplayName("une valeur imbriquée est un Monomorphic, jamais une Map brute")
		void nestedValuesStayMonomorphic() {
			// Le défaut de fond de Truc : fromMap() n'enveloppait que la racine,
			// donc tout ce qui était dessous restait une Map et le code testait
			// tantôt instanceof Map, tantôt instanceof Truc.
			final Monomorphic root = Monomorphic.mapBuilder().put("child", Monomorphic.mapBuilder().putNull("x").build())
					.build();

			assertTrue(root.get("child").isMap());
			assertTrue(root.asMap().get("child").isMap());
			assertTrue(root.get("child").get("x").isNull());
		}

		@Test
		@DisplayName("une liste de listes reste navigable")
		void nestedLists() {
			final Monomorphic value = Monomorphic.createList(Monomorphic.createList(Monomorphic.createNumber(1L)),
					Monomorphic.createList());

			assertEquals(1L, value.get(0).get(0).asLong());
			assertEquals(0, value.get(1).size());
		}

		private Monomorphic position(final long line, final long character) {
			return Monomorphic.mapBuilder().putNumber("line", line).putNumber("character", character).build();
		}
	}

}
