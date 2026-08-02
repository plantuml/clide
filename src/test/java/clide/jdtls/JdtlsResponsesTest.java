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

import clide.json.Monomorphic;

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
	@DisplayName("positionParams() encode la ligne en 0-based et n'ajoute 'context' que si non-null")
	void positionParamsEncodesZeroBasedLineAndOptionalContext(@TempDir final Path dir) throws IOException {
		final Path file = Files.createFile(dir.resolve("A.java"));

		final Monomorphic withoutContext = JdtlsResponses.positionParams(file, 5, 12);
		assertEquals(4, withoutContext.getOrNull("position").getOrNull("line").longOrDefault(-1),
				"ligne 1-based 5 -> 0-based 4");
		assertEquals(12, withoutContext.getOrNull("position").getOrNull("character").longOrDefault(-1));
		assertTrue(withoutContext.getOrNull("context").isNull());

		final Monomorphic context = Monomorphic.mapBuilder().putBoolean("includeDeclaration", false).build();
		final Monomorphic withContext = JdtlsResponses.positionParams(file, 5, 12, context);
		assertEquals(context, withContext.getOrNull("context"));
	}

}
