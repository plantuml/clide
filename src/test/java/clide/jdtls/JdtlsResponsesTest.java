package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.core.Monomorphic;

/**
 * Tests des lectures/constructions pures de JdtlsResponses - tout est ici
 * fabrication/lecture de Monomorphic ou de fichiers réels, jamais un jdtls,
 * donc rien à monter pour tester. documentSymbols() n'est pas couvert ici :
 * c'est le seul membre qui parle réellement à un LspClient.
 */
class JdtlsResponsesTest {

	@Test
	@DisplayName("errorOf() distingue une erreur réelle d'un champ 'error' absent ou explicitement null")
	void errorOfDistinguishesRealErrorFromAbsentOrExplicitNull() {
		final Monomorphic withError = Monomorphic.mapBuilder()
				.put("error", Monomorphic.mapBuilder().putString("message", "boom").build()).build();
		assertTrue(JdtlsResponses.errorOf(withError).isMap());

		final Monomorphic withoutError = Monomorphic.mapBuilder().putString("result", "ok").build();
		assertNull(JdtlsResponses.errorOf(withoutError));

		final Monomorphic withExplicitNull = Monomorphic.mapBuilder().putNull("error").build();
		assertNull(JdtlsResponses.errorOf(withExplicitNull));
	}

	@Test
	@DisplayName("uriOf() retombe sur targetUri pour un LocationLink")
	void uriOfFallsBackToTargetUriForALocationLink() {
		final Monomorphic plainLocation = Monomorphic.mapBuilder().putString("uri", "file:///a.java").build();
		assertEquals("file:///a.java", JdtlsResponses.uriOf(plainLocation));

		final Monomorphic locationLink = Monomorphic.mapBuilder().putString("targetUri", "file:///b.java").build();
		assertEquals("file:///b.java", JdtlsResponses.uriOf(locationLink));
	}

	@Test
	@DisplayName("rangeOf() retombe sur targetSelectionRange pour un LocationLink")
	void rangeOfFallsBackToTargetSelectionRangeForALocationLink() {
		final Monomorphic range = Monomorphic.mapBuilder().putNumber("marker", 1).build();
		final Monomorphic plainLocation = Monomorphic.mapBuilder().put("range", range).build();
		assertEquals(range, JdtlsResponses.rangeOf(plainLocation));

		final Monomorphic locationLink = Monomorphic.mapBuilder().put("targetSelectionRange", range).build();
		assertEquals(range, JdtlsResponses.rangeOf(locationLink));
	}

	@Test
	@DisplayName("lineOf() rend -1 quand 'line' est absent, plutôt que de lever")
	void lineOfDefaultsToMinusOneWhenAbsent() {
		final Monomorphic position = Monomorphic.mapBuilder().putNumber("line", 41).build();
		assertEquals(41, JdtlsResponses.lineOf(position));
		assertEquals(-1, JdtlsResponses.lineOf(Monomorphic.createNull()));
	}

	@Test
	@DisplayName("rawLocations() accepte une Location seule, une liste, ou rien")
	void rawLocationsAcceptsASingleLocationAListOrNothing() {
		final Monomorphic single = Monomorphic.mapBuilder().putString("uri", "file:///a.java").build();
		assertEquals(List.of(single), JdtlsResponses.rawLocations(single));

		final Monomorphic other = Monomorphic.mapBuilder().putString("uri", "file:///b.java").build();
		final Monomorphic list = Monomorphic.createList(single, other, Monomorphic.createNull());
		assertEquals(List.of(single, other), JdtlsResponses.rawLocations(list),
				"un élément non-map (null) de la liste doit être filtré, pas planter");

		assertTrue(JdtlsResponses.rawLocations(Monomorphic.createNull()).isEmpty());
	}

	@Test
	@DisplayName("readLineSafely() lit la bonne ligne, strippée, ou rend null proprement")
	void readLineSafelyReadsTheRightLineOrFailsCleanly(@TempDir final Path dir) throws IOException {
		final Path file = dir.resolve("Sample.java");
		Files.writeString(file, "class Sample {\n    void foo() {}\n}\n", StandardCharsets.UTF_8);
		final String uri = file.toUri().toString();

		assertEquals("void foo() {}", JdtlsResponses.readLineSafely(uri, 2));
		assertNull(JdtlsResponses.readLineSafely(uri, 999), "ligne hors bornes -> null, pas d'exception");
		assertNull(JdtlsResponses.readLineSafely(uri, 0), "ligne < 1 -> null sans même essayer de lire");
		assertNull(JdtlsResponses.readLineSafely(null, 1));
		assertNull(JdtlsResponses.readLineSafely("not a valid uri", 1), "URI malformée -> null, pas d'exception");
	}

	@Test
	@DisplayName("childrenOf() rend une liste vide, jamais null, quand 'children' est absent")
	void childrenOfIsEmptyNotNullWhenAbsent() {
		final Monomorphic child = Monomorphic.mapBuilder().putString("name", "foo").build();
		final Monomorphic withChildren = Monomorphic.mapBuilder().putList("children", List.of(child)).build();
		assertEquals(List.of(child), JdtlsResponses.childrenOf(withChildren));

		assertTrue(JdtlsResponses.childrenOf(Monomorphic.mapBuilder().build()).isEmpty());
	}

	@Test
	@DisplayName("isTypeKind() reconnaît class/enum/interface/struct, pas method")
	void isTypeKindRecognizesTypeKindsOnly() {
		assertTrue(JdtlsResponses.isTypeKind(nodeOfKind(5)), "class");
		assertTrue(JdtlsResponses.isTypeKind(nodeOfKind(10)), "enum");
		assertTrue(JdtlsResponses.isTypeKind(nodeOfKind(11)), "interface");
		assertTrue(JdtlsResponses.isTypeKind(nodeOfKind(23)), "struct");
		assertFalse(JdtlsResponses.isTypeKind(nodeOfKind(6)), "method");
	}

	private static Monomorphic nodeOfKind(final int kind) {
		return Monomorphic.mapBuilder().putNumber("kind", kind).build();
	}

	@Test
	@DisplayName("positionParams() encode ligne ET colonne en 0-based, et n'ajoute 'context' que si non-null")
	void positionParamsEncodesZeroBasedLineAndColumnAndOptionalContext(@TempDir final Path dir) throws IOException {
		final Path file = Files.createFile(dir.resolve("A.java"));

		final Monomorphic withoutContext = JdtlsResponses.positionParams(file, 5, 12);
		assertEquals(4, withoutContext.getOrNull("position").getOrNull("line").longOrDefault(-1),
				"ligne 1-based 5 -> 0-based 4");
		assertEquals(11, withoutContext.getOrNull("position").getOrNull("character").longOrDefault(-1),
				"colonne 1-based 12 -> 0-based 11");
		assertTrue(withoutContext.getOrNull("context").isNull());

		final Monomorphic context = Monomorphic.mapBuilder().putBoolean("includeDeclaration", false).build();
		final Monomorphic withContext = JdtlsResponses.positionParams(file, 5, 12, context);
		assertEquals(context, withContext.getOrNull("context"));
	}

	@Test
	@DisplayName("lineOf()/characterOf() rendent -1 quand le champ manque, jamais 0")
	void rawCoordinatesReportAbsenceRatherThanZero() {
		final Monomorphic start = Monomorphic.mapBuilder().putNumber("line", 3).putNumber("character", 17).build();
		assertEquals(3, JdtlsResponses.lineOf(start));
		assertEquals(17, JdtlsResponses.characterOf(start));

		assertEquals(-1, JdtlsResponses.lineOf(Monomorphic.createNull()));
		assertEquals(-1, JdtlsResponses.characterOf(Monomorphic.createNull()));
	}

	@Test
	@DisplayName("oneBased() ajoute 1, sauf à -1 qui reste -1 - une absence n'est pas la colonne 0")
	void oneBasedKeepsAbsenceAsAbsence() {
		assertEquals(1, JdtlsResponses.oneBased(0));
		assertEquals(18, JdtlsResponses.oneBased(17));
		assertEquals(-1, JdtlsResponses.oneBased(-1));
	}

	@Test
	@DisplayName("identifierAt() rend le mot qui commence exactement à cette colonne")
	void identifierAtReadsTheWordStartingThere() {
		// "\t\tréturn add(add(a, 1));" sans accent : tab(1) tab(2) return(3-8)
		// espace(9) add(10-12) ((13) add(14-16).
		final String line = "\t\treturn add(add(a, 1));";

		assertEquals("return", JdtlsResponses.identifierAt(line, 3));
		assertEquals("add", JdtlsResponses.identifierAt(line, 10));
		assertEquals("add", JdtlsResponses.identifierAt(line, 14));
	}

	@Test
	@DisplayName("identifierAt() ne devine jamais : ni au milieu d'un mot, ni sur autre chose qu'un mot")
	void identifierAtNeverGuesses() {
		final String line = "\t\treturn add(a);";

		assertEquals("", JdtlsResponses.identifierAt(line, 4), "au milieu de 'return'");
		assertEquals("", JdtlsResponses.identifierAt(line, 1), "sur une tabulation");
		assertEquals("", JdtlsResponses.identifierAt(line, 13), "sur une parenthèse");
		assertEquals("", JdtlsResponses.identifierAt(line, 999), "au delà de la ligne");
		assertEquals("", JdtlsResponses.identifierAt(line, 0), "colonne 0 - la numérotation commence à 1");
		assertEquals("", JdtlsResponses.identifierAt(null, 3), "ligne illisible");
	}

	@Test
	@DisplayName("readLineSafely() rend la ligne nettoyée, readRawLineSafely() la ligne brute - les colonnes se comptent sur la brute")
	void rawLineKeepsIndentationSoColumnsStayValid(@TempDir final Path dir) throws IOException {
		final Path file = dir.resolve("A.java");
		Files.writeString(file, "class A {\n\t\tint total;\n}\n", StandardCharsets.UTF_8);
		final String uri = file.toUri().toString();

		assertEquals("int total;", JdtlsResponses.readLineSafely(uri, 2));
		assertEquals("\t\tint total;", JdtlsResponses.readRawLineSafely(uri, 2));

		// La colonne 7 est celle de "total" sur la ligne brute ; sur la ligne
		// nettoyée elle tomberait ailleurs - c'est tout l'intérêt des deux formes.
		assertEquals("total", JdtlsResponses.identifierAt(JdtlsResponses.readRawLineSafely(uri, 2), 7));
	}

}
