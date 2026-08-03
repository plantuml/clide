package clide.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import clide.core.Monomorphic;
import clide.core.MonomorphicType;

/**
 * Tests de Json2, le lecteur/écrivain JSON qui parle Monomorphic là où Json
 * parle Object et Truc.
 *
 * Trois propriétés structurent ce fichier :
 *
 * - parse() rend un Monomorphic, jamais null et jamais une Map brute. Un test
 *   se lit donc assertEquals(attendu, Json2.parse(texte)), une seule assertion
 *   sur l'arbre entier, au lieu de descendre champ par champ en castant ;
 * - un entier reste un entier. L'id JSON-RPC sort d'un AtomicLong et sert à
 *   apparier une réponse avec la requête qui l'attend : 41 qui revient en 41.0
 *   est un appelant qui attend son timeout pour rien. D'où INTEGER vs DECIMAL,
 *   et d'où le fait que write(parse(x)) soit l'identité sur les entiers ;
 * - toute entrée malformée lève IllegalArgumentException en nommant la
 *   position. Json laissait passer des StringIndexOutOfBoundsException (entrée
 *   coupée en plein milieu d'une chaîne, d'un tableau, d'un objet) et acceptait
 *   sans rien dire des documents que personne d'autre ne lit (zéro en tête,
 *   signe moins tout seul, caractère de contrôle brut dans une chaîne).
 */
class JsonTest {

	// ==================================================================
	// Écriture
	// ==================================================================

	@Nested
	@DisplayName("Écriture des scalaires")
	class WritingScalars {

		@Test
		@DisplayName("null, true et false s'écrivent en toutes lettres")
		void literals() {
			assertEquals("null", Json.write(Monomorphic.createNull()));
			assertEquals("true", Json.write(Monomorphic.createBoolean(true)));
			assertEquals("false", Json.write(Monomorphic.createBoolean(false)));
		}

		@Test
		@DisplayName("un entier s'écrit sans '.0' - c'est tout l'intérêt d'INTEGER")
		void integersStayIntegers() {
			assertEquals("41", Json.write(Monomorphic.createNumber(41L)));
			assertEquals("0", Json.write(Monomorphic.createNumber(0L)));
			assertEquals("-7", Json.write(Monomorphic.createNumber(-7L)));
		}

		@Test
		@DisplayName("les bornes d'un long passent sans perte")
		void longBounds() {
			assertEquals("9223372036854775807", Json.write(Monomorphic.createNumber(Long.MAX_VALUE)));
			assertEquals("-9223372036854775808", Json.write(Monomorphic.createNumber(Long.MIN_VALUE)));
			// La valeur qu'un double ne sait déjà plus représenter exactement.
			assertEquals("9007199254740993", Json.write(Monomorphic.createNumber(9007199254740993L)));
		}

		@Test
		@DisplayName("un décimal garde sa partie fractionnaire")
		void decimals() {
			assertEquals("1.5", Json.write(Monomorphic.createNumber(1.5)));
			assertEquals("-0.25", Json.write(Monomorphic.createNumber(-0.25)));
			assertEquals("1.0", Json.write(Monomorphic.createNumber(1.0)));
		}

		@Test
		@DisplayName("NaN et l'infini sont refusés - JSON ne sait pas les écrire")
		void nonFiniteRefused() {
			for (final double value : new double[] { Double.NaN, Double.POSITIVE_INFINITY,
					Double.NEGATIVE_INFINITY }) {
				final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
						() -> Json.write(Monomorphic.createNumber(value)));
				assertTrue(thrown.getMessage().contains("Cannot serialize"), thrown.getMessage());
			}
		}

		@Test
		@DisplayName("write(null) est refusé - c'est createNull() qu'il faut")
		void rejectsJavaNull() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.write(null));

			assertTrue(thrown.getMessage().contains("createNull"), thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("Écriture des chaînes")
	class WritingStrings {

		@Test
		@DisplayName("une chaîne ordinaire est juste entourée de guillemets")
		void plain() {
			assertEquals("\"file:///tmp/A.java\"", Json.write(Monomorphic.createString("file:///tmp/A.java")));
			assertEquals("\"\"", Json.write(Monomorphic.createString("")));
		}

		@Test
		@DisplayName("guillemet et antislash sont échappés")
		void quoteAndBackslash() {
			assertEquals("\"a\\\"b\"", Json.write(Monomorphic.createString("a\"b")));
			assertEquals("\"C:\\\\github\\\\plantuml\"", Json.write(Monomorphic.createString("C:\\github\\plantuml")));
		}

		@Test
		@DisplayName("les six échappements courts sont utilisés plutôt que \\u")
		void shortEscapes() {
			assertEquals("\"\\n\\r\\t\\b\\f\"", Json.write(Monomorphic.createString("\n\r\t\b\f")));
		}

		@Test
		@DisplayName("les autres caractères de contrôle passent en \\u minuscule sur 4 chiffres")
		void controlCharacters() {
			assertEquals("\"\\u0000\"", Json.write(Monomorphic.createString("\u0000")));
			assertEquals("\"\\u001f\"", Json.write(Monomorphic.createString("\u001f")));
			assertEquals("\"\\u000b\"", Json.write(Monomorphic.createString("\u000b")));
		}

		@Test
		@DisplayName("l'oblique et l'unicode imprimable ne sont pas échappés - inutile, et illisible")
		void nothingElseIsEscaped() {
			assertEquals("\"a/b\"", Json.write(Monomorphic.createString("a/b")));
			assertEquals("\"éàü\"", Json.write(Monomorphic.createString("éàü")));
			assertEquals("\"\uD83D\uDE00\"", Json.write(Monomorphic.createString("\uD83D\uDE00")));
		}

		@Test
		@DisplayName("un substitut orphelin est échappé - brut, il n'a pas d'encodage UTF-8")
		void unpairedSurrogatesAreEscaped() {
			assertEquals("\"\\ud83d\"", Json.write(Monomorphic.createString("\uD83D")));
			assertEquals("\"\\ude00\"", Json.write(Monomorphic.createString("\uDE00")));
			assertEquals("\"x\\ud83dy\"", Json.write(Monomorphic.createString("x\uD83Dy")));
			// Deux hauts de suite : le premier est orphelin, le second forme la paire.
			assertEquals("\"\\ud83d\uD83D\uDE00\"", Json.write(Monomorphic.createString("\uD83D\uD83D\uDE00")));
		}

		@Test
		@DisplayName("un substitut orphelin survit au passage en octets UTF-8, contrairement au caractère brut")
		void unpairedSurrogateSurvivesTheWire() {
			final String raw = "avant\uD83Daprès";

			// Ce que LspClient fait de ce que rend write() : String.getBytes(UTF_8).
			final byte[] wire = Json.write(Monomorphic.createString(raw)).getBytes(StandardCharsets.UTF_8);

			assertEquals(raw, Json.parse(new String(wire, StandardCharsets.UTF_8)).asString());
			// Sans l'échappement, l'aller-retour par les octets rendrait '?'.
			assertNotEquals(raw, new String(raw.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("une paire correcte reste en clair - l'échapper rendrait le JSON illisible pour rien")
		void wellFormedPairsStayRaw() {
			final String emoji = "\uD83D\uDE00";
			final byte[] wire = Json.write(Monomorphic.createString(emoji)).getBytes(StandardCharsets.UTF_8);

			assertEquals("\"" + emoji + "\"", new String(wire, StandardCharsets.UTF_8));
			assertEquals(emoji, Json.parse(new String(wire, StandardCharsets.UTF_8)).asString());
		}

		@Test
		@DisplayName("DEL et U+2028 restent en clair - ils s'encodent en UTF-8 sans problème")
		void deleteAndLineSeparatorStayRaw() {
			assertEquals("\"\u007f\"", Json.write(Monomorphic.createString("\u007f")));
			assertEquals("\"\u2028\"", Json.write(Monomorphic.createString("\u2028")));
		}

		@Test
		@DisplayName("une clé d'objet est échappée comme n'importe quelle chaîne")
		void keysAreEscapedToo() {
			assertEquals("{\"a\\nb\":1}", Json.write(Monomorphic.mapBuilder().putNumber("a\nb", 1L).build()));
		}
	}

	@Nested
	@DisplayName("Écriture des conteneurs")
	class WritingContainers {

		@Test
		@DisplayName("conteneurs vides")
		void empty() {
			assertEquals("[]", Json.write(Monomorphic.createList()));
			assertEquals("{}", Json.write(Monomorphic.mapBuilder().build()));
		}

		@Test
		@DisplayName("aucune espace superflue - ni après ':' ni après ','")
		void noWhitespace() {
			assertEquals("[1,2,3]", Json.write(Monomorphic.createList(Monomorphic.createNumber(1L),
					Monomorphic.createNumber(2L), Monomorphic.createNumber(3L))));
			assertEquals("{\"a\":1,\"b\":2}",
					Json.write(Monomorphic.mapBuilder().putNumber("a", 1L).putNumber("b", 2L).build()));
		}

		@Test
		@DisplayName("l'ordre des clés est celui de la construction, pas l'ordre alphabétique")
		void keyOrderIsPreserved() {
			assertEquals("{\"z\":1,\"a\":2,\"m\":3}", Json.write(
					Monomorphic.mapBuilder().putNumber("z", 1L).putNumber("a", 2L).putNumber("m", 3L).build()));
		}

		@Test
		@DisplayName("les valeurs mélangées et imbriquées descendent récursivement")
		void nested() {
			final Monomorphic value = Monomorphic.mapBuilder().putString("uri", "file:///A.java")
					.putList("items", List.of(Monomorphic.createNull(), Monomorphic.createBoolean(true),
							Monomorphic.createList(Monomorphic.createNumber(1.5))))
					.build();

			assertEquals("{\"uri\":\"file:///A.java\",\"items\":[null,true,[1.5]]}", Json.write(value));
		}
	}

	// ==================================================================
	// Lecture
	// ==================================================================

	@Nested
	@DisplayName("Lecture des scalaires")
	class ReadingScalars {

		@Test
		@DisplayName("un null JSON revient en Monomorphic NULL, jamais en null Java")
		void nullIsAValue() {
			final Monomorphic value = Json.parse("null");

			assertNotNull(value);
			assertTrue(value.isNull());
			assertEquals(Monomorphic.createNull(), value);
		}

		@Test
		@DisplayName("true et false")
		void booleans() {
			assertTrue(Json.parse("true").asBoolean());
			assertFalse(Json.parse("false").asBoolean());
			assertEquals(MonomorphicType.BOOLEAN, Json.parse("true").getType());
		}

		@Test
		@DisplayName("une chaîne")
		void strings() {
			assertEquals("hello", Json.parse("\"hello\"").asString());
			assertEquals("", Json.parse("\"\"").asString());
		}

		@Test
		@DisplayName("un entier reste INTEGER, un décimal reste DECIMAL")
		void integerVersusDecimal() {
			assertTrue(Json.parse("41").isInteger());
			assertEquals(41L, Json.parse("41").asLong());

			assertTrue(Json.parse("41.0").isDecimal());
			assertEquals(41.0, Json.parse("41.0").asDouble());

			// La distinction est portée par equals() : c'est ce qui fait échouer
			// un test si un jour parse() se met à tout rendre en double.
			assertNotEquals(Json.parse("41"), Json.parse("41.0"));
		}

		@Test
		@DisplayName("l'exposant fait un DECIMAL, même sans point")
		void exponentMakesADecimal() {
			assertTrue(Json.parse("1e3").isDecimal());
			assertEquals(1000.0, Json.parse("1e3").asDouble());
			assertEquals(1000.0, Json.parse("1E+3").asDouble());
			assertEquals(0.0015, Json.parse("1.5e-3").asDouble());
		}

		@Test
		@DisplayName("les négatifs et le zéro")
		void negativesAndZero() {
			assertEquals(-41L, Json.parse("-41").asLong());
			assertEquals(0L, Json.parse("0").asLong());
			assertEquals(0L, Json.parse("-0").asLong());
			assertEquals(-0.5, Json.parse("-0.5").asDouble());
		}

		@Test
		@DisplayName("les bornes d'un long sont exactes - l'id JSON-RPC en dépend")
		void longBoundsAreExact() {
			assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807").asLong());
			assertEquals(Long.MIN_VALUE, Json.parse("-9223372036854775808").asLong());
			assertEquals(9007199254740993L, Json.parse("9007199254740993").asLong());
		}

		@Test
		@DisplayName("un entier trop grand pour un long bascule en DECIMAL plutôt que d'échouer")
		void hugeIntegerFallsBackToDecimal() {
			final Monomorphic value = Json.parse("9223372036854775808");

			assertTrue(value.isDecimal());
			assertEquals(9.223372036854776E18, value.asDouble());
		}
	}

	@Nested
	@DisplayName("Lecture des échappements")
	class ReadingEscapes {

		@Test
		@DisplayName("les huit échappements de la spec")
		void allEscapes() {
			assertEquals("\"", Json.parse("\"\\\"\"").asString());
			assertEquals("\\", Json.parse("\"\\\\\"").asString());
			assertEquals("/", Json.parse("\"\\/\"").asString());
			assertEquals("\n", Json.parse("\"\\n\"").asString());
			assertEquals("\r", Json.parse("\"\\r\"").asString());
			assertEquals("\t", Json.parse("\"\\t\"").asString());
			assertEquals("\b", Json.parse("\"\\b\"").asString());
			assertEquals("\f", Json.parse("\"\\f\"").asString());
		}

		@Test
		@DisplayName("\\u, en minuscules comme en majuscules")
		void unicodeEscape() {
			assertEquals("é", Json.parse("\"\\u00e9\"").asString());
			assertEquals("é", Json.parse("\"\\u00E9\"").asString());
			assertEquals("\u0000", Json.parse("\"\\u0000\"").asString());
			assertEquals("A", Json.parse("\"\\u0041\"").asString());
		}

		@Test
		@DisplayName("une paire de substituts se recolle en un seul caractère")
		void surrogatePair() {
			final String parsed = Json.parse("\"\\ud83d\\ude00\"").asString();

			assertEquals("\uD83D\uDE00", parsed);
			assertEquals(1, parsed.codePointCount(0, parsed.length()));
		}

		@Test
		@DisplayName("un guillemet ou un antislash écrit en \\u ne referme pas la chaîne")
		void escapedQuoteAndBackslashViaUnicode() {
			// Le bug classique : traiter le caractère produit par \\u comme s'il
			// avait été lu tel quel, et voir la chaîne se terminer au milieu.
			assertEquals("a\"b", Json.parse("\"a\\u0022b\"").asString());
			assertEquals("a\\b", Json.parse("\"a\\u005cb\"").asString());
			assertEquals("a\\u0022b", Json.parse("\"a\\\\u0022b\"").asString());
		}

		@Test
		@DisplayName("un substitut orphelin écrit en \\u est accepté et ressort échappé")
		void loneSurrogateEscape() {
			final Monomorphic value = Json.parse("\"\\ud800\"");

			assertEquals(1, value.asString().length());
			assertEquals('\ud800', value.asString().charAt(0));
			assertEquals("\"\\ud800\"", Json.write(value));
		}

		@Test
		@DisplayName("un antislash au milieu d'un chemin Windows ressort tel quel")
		void windowsPath() {
			assertEquals("C:\\github\\plantuml", Json.parse("\"C:\\\\github\\\\plantuml\"").asString());
		}
	}

	@Nested
	@DisplayName("Lecture des conteneurs")
	class ReadingContainers {

		@Test
		@DisplayName("conteneurs vides")
		void empty() {
			assertEquals(Monomorphic.createList(), Json.parse("[]"));
			assertEquals(Monomorphic.mapBuilder().build(), Json.parse("{}"));
			assertEquals(0, Json.parse("[]").size());
			assertEquals(0, Json.parse("{}").size());
		}

		@Test
		@DisplayName("un arbre entier se compare d'un seul assertEquals")
		void wholeTreeInOneAssertion() {
			final Monomorphic expected = Monomorphic.mapBuilder().putString("jsonrpc", "2.0").putNumber("id", 1L)
					.putList("result", List.of(Monomorphic.mapBuilder().putString("uri", "file:///A.java").build()))
					.build();

			assertEquals(expected, Json.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[{\"uri\":\"file:///A.java\"}]}"));
		}

		@Test
		@DisplayName("rien n'est laissé en Map ou List brute sous la racine")
		void everythingIsMonomorphic() {
			final Monomorphic root = Json.parse("{\"a\":{\"b\":[{\"c\":1}]}}");

			assertTrue(root.isMap());
			assertTrue(root.getFromMap("a").isMap());
			assertTrue(root.getFromMap("a").getFromMap("b").isList());
			assertTrue(root.getFromMap("a").getFromMap("b").getFromList(0).isMap());
			assertEquals(1, root.getFromMap("a").getFromMap("b").getFromList(0).getFromMap("c").asInt());
		}

		@Test
		@DisplayName("l'ordre des clés du document est conservé")
		void keyOrderIsPreserved() {
			assertEquals(List.of("z", "a", "m"), List.copyOf(Json.parse("{\"z\":1,\"a\":2,\"m\":3}").asMap().keySet()));
		}

		@Test
		@DisplayName("une clé répétée : la dernière gagne")
		void duplicateKeyLastWins() {
			final Monomorphic value = Json.parse("{\"a\":1,\"a\":2}");

			assertEquals(1, value.size());
			assertEquals(2, value.getFromMap("a").asInt());
		}

		@Test
		@DisplayName("une clé répétée garde sa place d'origine dans l'ordre")
		void duplicateKeyKeepsItsFirstPosition() {
			final Monomorphic value = Json.parse("{\"a\":1,\"b\":2,\"a\":3}");

			assertEquals(List.of("a", "b"), List.copyOf(value.asMap().keySet()));
			assertEquals(3, value.getFromMap("a").asInt());
		}

		@Test
		@DisplayName("une clé vide, ou contenant un échappement, reste une clé")
		void unusualKeys() {
			assertEquals(1, Json.parse("{\"\":1}").getFromMap("").asInt());
			assertEquals(42, Json.parse("{\"a\\u0000b\":42}").getFromMap("a\u0000b").asInt());
			assertEquals("{\"a\\u0000b\":42}", Json.write(Json.parse("{\"a\\u0000b\":42}")));
		}

		@Test
		@DisplayName("une liste hétérogène garde le type de chaque élément")
		void heterogeneousList() {
			final Monomorphic value = Json.parse("[null,true,\"x\",1,1.5,[],{}]");

			assertEquals(7, value.size());
			assertTrue(value.getFromList(0).isNull());
			assertTrue(value.getFromList(1).isBoolean());
			assertTrue(value.getFromList(2).isString());
			assertTrue(value.getFromList(3).isInteger());
			assertTrue(value.getFromList(4).isDecimal());
			assertTrue(value.getFromList(5).isList());
			assertTrue(value.getFromList(6).isMap());
		}

		@Test
		@DisplayName("la map rendue est immuable - elle traverse les threads de LspClient")
		void parsedMapIsUnmodifiable() {
			assertThrows(UnsupportedOperationException.class,
					() -> Json.parse("{\"a\":1}").asMap().put("b", Monomorphic.createNull()));
			assertThrows(UnsupportedOperationException.class,
					() -> Json.parse("[1]").asList().add(Monomorphic.createNull()));
		}
	}

	@Nested
	@DisplayName("Espaces")
	class Whitespace {

		@Test
		@DisplayName("espaces, tabulations et retours à la ligne sont ignorés partout")
		void ignoredEverywhere() {
			final Monomorphic expected = Monomorphic.mapBuilder().putNumber("a", 1L)
					.putList("b", List.of(Monomorphic.createNumber(2L), Monomorphic.createNumber(3L))).build();

			assertEquals(expected, Json.parse("  {\n\t\"a\" : 1 ,\r\n\t\"b\" : [ 2 , 3 ]\n}  "));
		}

		@ParameterizedTest
		@DisplayName("seuls les quatre caractères de la spec comptent comme espace")
		@ValueSource(strings = { "\u00a0{}", "\u000b1", "\u000c1", "\ufeff{}", "\u00851", "\u001e1", "\u30001" })
		void onlySpecWhitespace(final String text) {
			// Une espace insécable, une tabulation verticale, un saut de page, un
			// BOM ou un séparateur d'enregistrement ne sont pas des espaces JSON :
			// les accepter ferait passer ici un texte que le pair suivant refuse.
			// Character.isWhitespace() en dirait oui pour plusieurs, d'où le test
			// explicite des quatre caractères de la spec dans Json2.
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@Test
		@DisplayName("un conteneur vide mais espacé reste vide")
		void spacedEmptyContainers() {
			assertEquals(0, Json.parse("{ }").size());
			assertEquals(0, Json.parse("[ ]").size());
			assertEquals(0, Json.parse("{\n\t}").size());
			assertEquals(0, Json.parse("[\r\n]").size());
		}

		@Test
		@DisplayName("des espaces après la valeur racine ne sont pas du contenu résiduel")
		void trailingWhitespaceIsFine() {
			assertTrue(Json.parse("null ").isNull());
			assertEquals(0, Json.parse(" [] \n\t").size());
			assertEquals(1L, Json.parse("1\n").asLong());
		}
	}

	// ==================================================================
	// Entrées malformées
	// ==================================================================

	@Nested
	@DisplayName("Entrée tronquée")
	class Truncated {

		@ParameterizedTest
		@DisplayName("une entrée coupée lève IllegalArgumentException, pas une erreur d'index")
		@ValueSource(strings = { "", "   ", "{", "[", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,", "[1", "[1,",
				"\"abc", "\"abc\\", "\"abc\\u12" })
		void truncatedInput(final String text) {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse(text));

			assertTrue(thrown.getMessage().contains("position"), thrown.getMessage());
		}

		@Test
		@DisplayName("la position pointe la fin du texte")
		void positionIsTheEnd() {
			assertEquals("Unexpected end of JSON at position 0", assertThrows(IllegalArgumentException.class,
					() -> Json.parse("")).getMessage());
			assertEquals("Unexpected end of JSON at position 6", assertThrows(IllegalArgumentException.class,
					() -> Json.parse("{\"a\":1")).getMessage());
		}

		@Test
		@DisplayName("parse(null) est refusé sans NullPointerException")
		void rejectsJavaNull() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse(null));

			assertTrue(thrown.getMessage().contains("null"), thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("Syntaxe invalide")
	class InvalidSyntax {

		@ParameterizedTest
		@DisplayName("structures mal formées")
		@ValueSource(strings = { "{\"a\"}", "{\"a\" 1}", "{a:1}", "{1:2}", "[1 2]", "[1;2]", "[,]", "[1,]", "{,}",
				"{\"a\":1,}", "}", "]", ":", ",", "undefined", "'x'" })
		void malformed(final String text) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@ParameterizedTest
		@DisplayName("du contenu après la valeur racine est une erreur, pas un silence")
		@ValueSource(strings = { "1 2", "{} {}", "null null", "[1][2]", "\"a\"\"b\"", "truex" })
		void trailingContent(final String text) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@ParameterizedTest
		@DisplayName("un littéral approximatif n'est pas accepté")
		@ValueSource(strings = { "tru", "nul", "flase", "True", "NULL", "t", "n" })
		void brokenLiterals(final String text) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@Test
		@DisplayName("le message d'erreur nomme le caractère fautif et sa position")
		void messageNamesTheCulprit() {
			assertEquals("Unexpected character '}' at position 0",
					assertThrows(IllegalArgumentException.class, () -> Json.parse("}")).getMessage());
			// La position est celle du caractère fautif, pas celle de la fin de la
			// valeur qui précède : les espaces sont sautées avant de se plaindre.
			// Un caractère de contrôle est nommé par son code plutôt que recopié
			// tel quel : le message finit dans un log, pas dans un terminal.
			assertEquals("Unexpected character '\\u0000' at position 0",
					assertThrows(IllegalArgumentException.class, () -> Json.parse("\u0000")).getMessage());
			assertEquals("Expected ',' or '}' at position 7",
					assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1 2}")).getMessage());
		}
	}

	@Nested
	@DisplayName("Nombres invalides")
	class InvalidNumbers {

		@ParameterizedTest
		@DisplayName("la grammaire des nombres est celle de JSON, pas celle de Double.parseDouble")
		@ValueSource(strings = { "-", "+1", ".5", "1.", "1.e3", "1e", "1e+", "1e-", "--1", "1..2", "0x1F", "Infinity",
				"NaN", "1d", "1f", "1_000", "1eE2", "0.e1", "9.e+", "1+2", "-123.123foo", "- 1", "-1.0.", "1e2e3" })
		void malformedNumbers(final String text) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@ParameterizedTest
		@DisplayName("un zéro en tête est refusé - '007' n'est pas du JSON")
		@ValueSource(strings = { "01", "007", "-01", "00", "0123" })
		void leadingZero(final String text) {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse(text));

			assertTrue(thrown.getMessage().contains("leading zero"), thrown.getMessage());
		}

		@Test
		@DisplayName("un zéro seul, ou suivi d'un point, reste valide")
		void zeroIsStillFine() {
			assertEquals(0L, Json.parse("0").asLong());
			assertEquals(0.5, Json.parse("0.5").asDouble());
			assertEquals(0.0, Json.parse("0e0").asDouble());
		}

		@Test
		@DisplayName("un exposant qui déborde du double est refusé plutôt que rendu en Infinity")
		void overflowRefused() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse("1e400"));

			assertTrue(thrown.getMessage().contains("out of range"), thrown.getMessage());
		}

		@Test
		@DisplayName("un entier sans exposant qui déborde du double est refusé de la même façon")
		void plainDigitOverflowRefused() {
			final String text = "1" + "0".repeat(400);

			assertTrue(assertThrows(IllegalArgumentException.class, () -> Json.parse(text)).getMessage()
					.contains("out of range"));
		}

		@Test
		@DisplayName("le message tronque le nombre fautif - il finit dans un log")
		void overflowMessageIsBounded() {
			final String message = assertThrows(IllegalArgumentException.class,
					() -> Json.parse("1" + "0".repeat(1_000_000))).getMessage();

			assertTrue(message.length() < 200, "message de " + message.length() + " caractères");
			assertTrue(message.contains("1000001 characters"), message);
		}

		@Test
		@DisplayName("le sous-dépassement s'aplatit à zéro - il n'y a rien à refuser")
		void underflowIsFlattened() {
			assertEquals(0.0, Json.parse("1e-400").asDouble());
			// Le signe survit à l'aplatissement : -1e-400 rend -0.0, pas 0.0.
			assertEquals(-0.0, Json.parse("-1e-400").asDouble());
			assertEquals("-0.0", Json.write(Json.parse("-1e-400")));
		}

		@Test
		@DisplayName("un long négatif qui déborde bascule en DECIMAL, comme le positif")
		void negativeLongUnderflowFallsBackToDecimal() {
			final Monomorphic value = Json.parse("-9223372036854775809");

			assertTrue(value.isDecimal());
			assertEquals(-9.223372036854776E18, value.asDouble());
		}

		@Test
		@DisplayName("les écritures d'exposant admises par la spec")
		void exponentSpellings() {
			assertEquals(100000.0, Json.parse("1e05").asDouble());
			assertEquals(100000.0, Json.parse("1E+05").asDouble());
			assertEquals(0.0, Json.parse("0e-0").asDouble());
			assertEquals(-0.0, Json.parse("-0.0e+12").asDouble());
			assertEquals(123e65, Json.parse("123e65").asDouble());
		}

		@Test
		@DisplayName("la position du zéro en tête est celle du premier chiffre, pas celle du signe")
		void leadingZeroPosition() {
			assertEquals("Number at position 1 has a leading zero",
					assertThrows(IllegalArgumentException.class, () -> Json.parse("-01")).getMessage());
		}

		@Test
		@DisplayName("un chiffre non-ASCII n'est pas un chiffre")
		void nonAsciiDigit() {
			// Character.isDigit() dit oui, Long.parseLong() aussi : c'est le piège
			// que la grammaire évite en testant '0'..'9' à la main.
			assertThrows(IllegalArgumentException.class, () -> Json.parse("\u0663"));
		}
	}

	@Nested
	@DisplayName("Chaînes invalides")
	class InvalidStrings {

		@Test
		@DisplayName("un échappement inconnu est refusé")
		void unknownEscape() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse("\"a\\xb\""));

			assertTrue(thrown.getMessage().contains("Unknown escape sequence"), thrown.getMessage());
		}

		@ParameterizedTest
		@DisplayName("un \\u mal formé est refusé")
		@ValueSource(strings = { "\"\\u12g4\"", "\"\\u 123\"", "\"\\u+123\"", "\"\\u12\"", "\"\\u\"" })
		void badUnicodeEscape(final String text) {
			assertThrows(IllegalArgumentException.class, () -> Json.parse(text));
		}

		@Test
		@DisplayName("un caractère de contrôle brut dans une chaîne est refusé")
		void rawControlCharacter() {
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse("\"a\nb\""));

			assertTrue(thrown.getMessage().contains("Unescaped control character"), thrown.getMessage());
			assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\tb\""));
			assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\u0000b\""));
		}

		@Test
		@DisplayName("un caractère de contrôle dans un message d'erreur est rendu, jamais recopié")
		void errorMessagesAreLogSafe() {
			// Un message qui recopie l'octet fautif casse la ligne de log qui
			// l'accueille - et, pour un substitut orphelin, ne s'encode même pas
			// en UTF-8. Trois chemins de message, la même règle.
			for (final String text : new String[] { "\"\\\n\"", "\"\\\u0000\"", "\"\\\ud800\"",
					"\"\\u12\u0001\u0002\"" }) {
				final String message = assertThrows(IllegalArgumentException.class, () -> Json.parse(text))
						.getMessage();

				for (int i = 0; i < message.length(); i++) {
					final char c = message.charAt(i);
					assertTrue(c >= 0x20 && Character.isSurrogate(c) == false,
							"message non imprimable : " + message.replaceAll("\\p{Cntrl}", "?"));
				}
			}
		}

		@Test
		@DisplayName("une clé d'objet doit être une chaîne entre guillemets")
		void keysMustBeStrings() {
			assertEquals("Expected '\"' at position 1",
					assertThrows(IllegalArgumentException.class, () -> Json.parse("{a:1}")).getMessage());
		}
	}

	@Nested
	@DisplayName("Profondeur")
	class Depth {

		/** La limite annoncée par Json2.MAX_DEPTH, en nombre de conteneurs. */
		private static final int MAX_DEPTH = 200;

		@Test
		@DisplayName("une imbrication raisonnable passe")
		void reasonableNestingWorks() {
			final int depth = 150;
			final Monomorphic value = Json.parse("[".repeat(depth) + "]".repeat(depth));

			Monomorphic current = value;
			for (int i = 0; i < depth - 1; i++) {
				assertEquals(1, current.size());
				current = current.getFromList(0);
			}
			assertEquals(0, current.size());
		}

		@Test
		@DisplayName("la limite est exactement MAX_DEPTH conteneurs, pour des tableaux")
		void arrayBoundaryIsExact() {
			assertEquals(MAX_DEPTH - 1, depthOf(Json.parse(nestedArrays(MAX_DEPTH))));

			assertThrows(IllegalArgumentException.class, () -> Json.parse(nestedArrays(MAX_DEPTH + 1)));
		}

		@Test
		@DisplayName("la limite est la même pour des objets - pas de dissymétrie entre les deux")
		void objectBoundaryIsTheSame() {
			assertNotNull(Json.parse(nestedObjects(MAX_DEPTH)));

			assertThrows(IllegalArgumentException.class, () -> Json.parse(nestedObjects(MAX_DEPTH + 1)));
		}

		@Test
		@DisplayName("une imbrication délirante lève une exception, pas un StackOverflowError")
		void absurdNestingIsRefused() {
			final String text = nestedArrays(50_000);

			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.parse(text));

			assertTrue(thrown.getMessage().contains("nested deeper"), thrown.getMessage());
		}

		@Test
		@DisplayName("idem pour des objets, et même sans les fermetures")
		void absurdNestingOfObjects() {
			assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":".repeat(50_000)));
		}

		@Test
		@DisplayName("tout ce que parse() rend, write() sait le réécrire - même au maximum")
		void whatParsesCanBeWrittenBack() {
			final String text = nestedArrays(MAX_DEPTH);

			assertEquals(text, Json.write(Json.parse(text)));
		}

		@Test
		@DisplayName("write() refuse aussi le trop profond, plutôt que d'exploser la pile")
		void writeRefusesTooDeep() {
			// Inatteignable depuis parse(), qui plafonne au même endroit ; mais une
			// valeur construite en mémoire, elle, peut descendre aussi bas qu'on veut.
			Monomorphic nested = Monomorphic.createNull();
			for (int i = 0; i < MAX_DEPTH + 1; i++)
				nested = Monomorphic.createList(nested);

			final Monomorphic value = nested;
			final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> Json.write(value));

			assertTrue(thrown.getMessage().contains("nested deeper"), thrown.getMessage());
		}

		@Test
		@DisplayName("write() accepte exactement MAX_DEPTH conteneurs")
		void writeBoundaryIsExact() {
			Monomorphic value = Monomorphic.createNull();
			for (int i = 0; i < MAX_DEPTH; i++)
				value = Monomorphic.createList(value);

			assertEquals("[".repeat(MAX_DEPTH) + "null" + "]".repeat(MAX_DEPTH), Json.write(value));
		}

		private static String nestedArrays(final int depth) {
			return "[".repeat(depth) + "]".repeat(depth);
		}

		private static String nestedObjects(final int depth) {
			return "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
		}

		private static int depthOf(final Monomorphic value) {
			int depth = 0;
			Monomorphic current = value;
			while (current.isList() && current.size() == 1) {
				depth++;
				current = current.getFromList(0);
			}
			return depth;
		}
	}

	// ==================================================================
	// Aller-retour
	// ==================================================================

	@Nested
	@DisplayName("Aller-retour")
	class RoundTrip {

		@ParameterizedTest
		@DisplayName("write(parse(x)) rend x - texte inchangé, y compris sur les entiers")
		@ValueSource(strings = { "null", "true", "false", "0", "41", "-41", "9223372036854775807",
				"-9223372036854775808", "1.5", "-0.25", "1.0", "\"\"", "\"hello\"", "\"a\\\"b\"", "\"a\\\\b\"",
				"\"\\n\"", "[]", "{}", "[1,2,3]", "{\"a\":1}", "{\"a\":[1,{\"b\":null}],\"c\":true}",
				"{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"textDocument/definition\"}" })
		void textIsPreserved(final String text) {
			assertEquals(text, Json.write(Json.parse(text)));
		}

		@ParameterizedTest
		@DisplayName("parse(write(v)) rend v - valeur inchangée")
		@ValueSource(strings = { "null", "true", "1.5", "41", "\"éàü\"", "\"\uD83D\uDE00\"", "[]", "{}",
				"{\"a\":[1,2],\"b\":{\"c\":\"d\"}}" })
		void valueIsPreserved(final String text) {
			final Monomorphic value = Json.parse(text);

			assertEquals(value, Json.parse(Json.write(value)));
		}

		@Test
		@DisplayName("les formes équivalentes d'un décimal sont normalisées à l'écriture")
		void decimalsAreNormalized() {
			// L'aller-retour n'est l'identité textuelle que sur les formes
			// canoniques : 1e3 est bien relu comme 1000.0, mais se réécrit tel que
			// Java écrit un double.
			assertEquals("1000.0", Json.write(Json.parse("1e3")));
			assertEquals("1.0E10", Json.write(Json.parse("1e10")));
			assertEquals("1.0E10", Json.write(Json.parse("1.0E10")));
		}

		@Test
		@DisplayName("le zéro négatif entier se réécrit '0' - un long n'a pas de -0")
		void negativeZeroIntegerIsNormalized() {
			assertTrue(Json.parse("-0").isInteger());
			assertEquals("0", Json.write(Json.parse("-0")));

			// Le -0.0 décimal, lui, est bien conservé : c'est un double, il a un
			// signe, et Monomorphic distingue les deux valeurs.
			assertTrue(Json.parse("-0.0").isDecimal());
			assertEquals("-0.0", Json.write(Json.parse("-0.0")));
		}

		@Test
		@DisplayName("les caractères de contrôle survivent à l'aller-retour")
		void controlCharactersSurvive() {
			final String raw = "ligne 1\nligne 2\ttabulée\u0001\u001f fin";
			final Monomorphic value = Monomorphic.createString(raw);

			assertEquals(raw, Json.parse(Json.write(value)).asString());
		}

		@Test
		@DisplayName("un chemin Windows survit à l'aller-retour")
		void windowsPathSurvives() {
			final String raw = "C:\\github\\plantuml\\src\\Main.java";

			assertEquals(raw, Json.parse(Json.write(Monomorphic.createString(raw))).asString());
		}
	}

	// ==================================================================
	// Trafic JSON-RPC réel
	// ==================================================================

	@Nested
	@DisplayName("Messages JSON-RPC")
	class JsonRpcTraffic {

		@Test
		@DisplayName("une requête initialize s'écrit telle que jdtls l'attend")
		void writesAnInitializeRequest() {
			final Monomorphic params = Monomorphic.mapBuilder().putNumber("processId", 4242L)
					.putString("rootUri", "file:///tmp/projet").putNull("trace").build();
			final Monomorphic request = Monomorphic.mapBuilder().putString("jsonrpc", "2.0").putNumber("id", 1L)
					.putString("method", "initialize").put("params", params).build();

			assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
					+ "\"params\":{\"processId\":4242,\"rootUri\":\"file:///tmp/projet\",\"trace\":null}}",
					Json.write(request));
		}

		@Test
		@DisplayName("une réponse textDocument/definition se navigue sans un seul cast")
		void readsADefinitionResponse() {
			final Monomorphic response = Json.parse("{\"jsonrpc\":\"2.0\",\"id\":41,\"result\":[{"
					+ "\"uri\":\"file:///tmp/projet/src/A.java\","
					+ "\"range\":{\"start\":{\"line\":6,\"character\":13},\"end\":{\"line\":6,\"character\":17}}}]}");

			assertEquals(41L, response.getFromMap("id").asLong());
			assertFalse(response.containsKey("error"));

			final Monomorphic location = response.getFromMap("result").getFromList(0);
			assertEquals("file:///tmp/projet/src/A.java", location.getFromMap("uri").asString());
			assertEquals(6, location.getFromMap("range").getFromMap("start").getFromMap("line").asInt());
			assertEquals(17, location.getFromMap("range").getFromMap("end").getFromMap("character").asInt());
		}

		@Test
		@DisplayName("l'id garde sa valeur exacte au-delà de 2^53 - l'appariement requête/réponse en dépend")
		void bigIdSurvives() {
			final long id = 9007199254740993L;
			final String text = Json.write(Monomorphic.mapBuilder().putNumber("id", id).build());

			assertEquals("{\"id\":9007199254740993}", text);
			assertEquals(id, Json.parse(text).getFromMap("id").asLong());
		}

		@Test
		@DisplayName("une réponse d'erreur reste lisible, y compris son 'data' de forme libre")
		void readsAnErrorResponse() {
			final Monomorphic response = Json
					.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,"
							+ "\"message\":\"Unsupported method\",\"data\":[\"a\",1,null]}}");

			final Monomorphic error = response.getFromMap("error");
			assertEquals(-32601, error.getFromMap("code").asInt());
			assertEquals("Unsupported method", error.getFromMap("message").asString());
			assertEquals(3, error.getFromMap("data").size());
		}

		@Test
		@DisplayName("un contenu de fichier avec sauts de ligne fait l'aller-retour intact")
		void documentContentSurvives() {
			final String source = "package a;\n\npublic class A {\n\tvoid m() {\n\t}\n}\n";
			final Monomorphic message = Monomorphic.mapBuilder().putString("text", source).build();

			assertEquals("{\"text\":\"package a;\\n\\npublic class A {\\n\\tvoid m() {\\n\\t}\\n}\\n\"}",
					Json.write(message));
			assertEquals(source, Json.parse(Json.write(message)).getFromMap("text").asString());
		}
	}

	// ==================================================================
	// Aléatoire
	// ==================================================================

	@Nested
	@DisplayName("Aller-retour sur des arbres tirés au hasard")
	class GenerativeRoundTrip {

		/**
		 * Les cas ci-dessus sont ceux auxquels on a pensé. Ceux-ci sont ceux
		 * auxquels on n'a pas pensé : des arbres tirés au sort, écrits, relus,
		 * comparés. La graine est fixe pour qu'un échec soit rejouable.
		 */
		@Test
		@DisplayName("mille arbres quelconques survivent à write() puis parse()")
		void randomTreesSurviveTheRoundTrip() {
			final Random random = new Random(20260801L);
			for (int i = 0; i < 1000; i++) {
				final Monomorphic value = randomValue(random, 0);

				final String text = Json.write(value);
				assertEquals(value, Json.parse(text), "arbre " + i + " : " + text);
				assertEquals(text, Json.write(Json.parse(text)), "arbre " + i);
			}
		}

		/**
		 * Le même aller-retour, mais en repassant par les octets - c'est la seule
		 * forme qui compte : LspClient écrit du UTF-8 sur un tuyau et relit du
		 * UTF-8 à l'autre bout. Un substitut orphelin laissé en clair passerait le
		 * test ci-dessus et échouerait ici.
		 */
		@Test
		@DisplayName("mille arbres quelconques survivent aussi au passage en octets UTF-8")
		void randomTreesSurviveTheWire() {
			final Random random = new Random(1789L);
			for (int i = 0; i < 1000; i++) {
				final Monomorphic value = randomValue(random, 0);

				final byte[] wire = Json.write(value).getBytes(StandardCharsets.UTF_8);

				assertEquals(value, Json.parse(new String(wire, StandardCharsets.UTF_8)), "arbre " + i);
			}
		}

		private Monomorphic randomValue(final Random random, final int depth) {
			final int kind = random.nextInt(depth < 4 ? 9 : 7);
			return switch (kind) {
			case 0 -> Monomorphic.createNull();
			case 1 -> Monomorphic.createBoolean(random.nextBoolean());
			case 2 -> Monomorphic.createNumber(random.nextLong());
			case 3 -> Monomorphic.createNumber(random.nextInt(2000) - 1000L);
			case 4 -> Monomorphic.createNumber(randomFiniteDouble(random));
			case 5, 6 -> Monomorphic.createString(randomString(random));
			case 7 -> randomList(random, depth);
			default -> randomMap(random, depth);
			};
		}

		private Monomorphic randomList(final Random random, final int depth) {
			final List<Monomorphic> values = new ArrayList<>();
			final int size = random.nextInt(4);
			for (int i = 0; i < size; i++)
				values.add(randomValue(random, depth + 1));

			return Monomorphic.createList(values);
		}

		private Monomorphic randomMap(final Random random, final int depth) {
			final Monomorphic.Builder builder = Monomorphic.mapBuilder();
			final int size = random.nextInt(4);
			for (int i = 0; i < size; i++)
				// Les clés sont distinctes : deux clés identiques feraient
				// légitimement diverger l'arbre construit et l'arbre relu.
				builder.put("k" + i + randomString(random), randomValue(random, depth + 1));

			return builder.build();
		}

		/** Fini et non NaN : le reste, write() le refuse, et c'est testé ailleurs. */
		private double randomFiniteDouble(final Random random) {
			while (true) {
				final double value = Double.longBitsToDouble(random.nextLong());
				if (Double.isNaN(value) == false && Double.isInfinite(value) == false)
					return value;
			}
		}

		/**
		 * Volontairement hostile : caractères de contrôle, guillemets, antislashs,
		 * accents, emoji et substituts orphelins - tout ce qui casse un écrivain
		 * naïf.
		 */
		private String randomString(final Random random) {
			final StringBuilder out = new StringBuilder();
			final int length = random.nextInt(12);
			for (int i = 0; i < length; i++)
				out.append(randomChar(random));

			return out.toString();
		}

		private char randomChar(final Random random) {
			return switch (random.nextInt(8)) {
			case 0 -> (char) random.nextInt(0x20);
			case 1 -> '"';
			case 2 -> '\\';
			case 3 -> (char) (0xd800 + random.nextInt(0x800));
			case 4 -> (char) (0xe0 + random.nextInt(0x20));
			case 5 -> '/';
			default -> (char) (' ' + random.nextInt(0x5f));
			};
		}
	}

}
