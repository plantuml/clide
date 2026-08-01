package clide.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de UnifiedDiff, le rendu de diff unifié maison (clide reste sans
 * dépendance, voir CLAUDE.md).
 *
 * Chaque sortie attendue ci-dessous est celle que produit `diff -u` de GNU
 * diffutils sur les mêmes entrées, aux deux lignes d'en-tête près (`diff -u`
 * y ajoute des horodatages, UnifiedDiff se contente des libellés reçus) et à
 * la dernière fin de ligne près (UnifiedDiff fait un stripTrailing()).
 * L'oracle est donc externe et vérifiable à la main :
 *
 *     printf 'A\nB\n...' &gt; before ; printf 'A\nB2\n...' &gt; after
 *     diff -u --label before --label after before after
 *
 * C'est ce qui donne leur valeur aux cas limites du format (@@ -0,0, fusion
 * de hunks) : ce ne sont pas les conventions que clide s'est choisies, ce
 * sont celles de diff(1), et un écart est un bug, pas un choix.
 */
class UnifiedDiffTest {

	/** 10 lignes, assez pour que 3 lignes de contexte ne couvrent pas tout. */
	private static final List<String> TEN_LINES = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");

	@Test
	@DisplayName("deux contenus identiques ne produisent rien du tout, pas même l'en-tête")
	void identicalInputsRenderNothing() {
		assertEquals("", UnifiedDiff.render(TEN_LINES, TEN_LINES, "before", "after"));
	}

	@Test
	@DisplayName("les libellés reçus forment les lignes --- et +++")
	void labelsFormTheHeader() {
		final String diff = UnifiedDiff.render(List.of("A"), List.of("B"), "a/Foo.java", "b/Foo.java");

		assertEquals("""
				--- a/Foo.java
				+++ b/Foo.java
				@@ -1,1 +1,1 @@
				-A
				+B""", diff);
	}

	@Test
	@DisplayName("un changement isolé garde 3 lignes de contexte de chaque côté")
	void singleChangeKeepsThreeLinesOfContext() {
		final List<String> after = List.of("A", "B", "C", "D", "E2", "F", "G", "H", "I", "J");

		// diff -u : @@ -2,7 +2,7 @@ - le hunk part de la ligne 2 (3 lignes de
		// contexte avant le changement en ligne 5) et couvre 7 lignes. A, I et J
		// sont hors contexte et n'apparaissent pas.
		assertEquals("""
				--- before
				+++ after
				@@ -2,7 +2,7 @@
				 B
				 C
				 D
				-E
				+E2
				 F
				 G
				 H""", render(TEN_LINES, after));
	}

	@Test
	@DisplayName("insertion dans un contenu vide : le côté gauche est annoncé -0,0")
	void insertionIntoEmptyBeforeReportsZeroLengthLeftSide() {
		// Cas limite du format : un hunk sans aucune ligne d'un côté annonce ce
		// côté à la ligne *précédant* le point d'insertion, d'où le 0 - et non 1.
		assertEquals("""
				--- before
				+++ after
				@@ -0,0 +1,2 @@
				+X
				+Y""", render(List.of(), List.of("X", "Y")));
	}

	@Test
	@DisplayName("suppression de tout le contenu : le côté droit est annoncé +0,0")
	void deletionOfEverythingReportsZeroLengthRightSide() {
		assertEquals("""
				--- before
				+++ after
				@@ -1,2 +0,0 @@
				-X
				-Y""", render(List.of("X", "Y"), List.of()));
	}

	@Test
	@DisplayName("deux changements séparés par 6 lignes fusionnent en un seul hunk")
	void nearbyChangesMergeIntoASingleHunk() {
		// L5 et L12 changent : 6 lignes inchangées entre les deux (L6..L11), soit
		// exactement 2*CONTEXT. Les deux contextes de 3 lignes se recouvriraient,
		// donc diff -u ne coupe pas et bridge le trou.
		final List<String> before = sixteenLines();
		final List<String> after = replace(replace(before, "L5", "L5x"), "L12", "L12x");

		assertEquals("""
				--- before
				+++ after
				@@ -2,14 +2,14 @@
				 L2
				 L3
				 L4
				-L5
				+L5x
				 L6
				 L7
				 L8
				 L9
				 L10
				 L11
				-L12
				+L12x
				 L13
				 L14
				 L15""", render(before, after));
	}

	@Test
	@DisplayName("deux changements éloignés produisent deux hunks distincts")
	void distantChangesProduceTwoHunks() {
		// 13 lignes inchangées entre les deux changements : au-delà de 2*CONTEXT,
		// diff -u coupe et les lignes du milieu ne sont pas rendues.
		final List<String> before = twentyLines();
		final List<String> after = replace(replace(before, "M3", "M3x"), "M17", "M17x");

		assertEquals("""
				--- before
				+++ after
				@@ -1,6 +1,6 @@
				 M1
				 M2
				-M3
				+M3x
				 M4
				 M5
				 M6
				@@ -14,7 +14,7 @@
				 M14
				 M15
				 M16
				-M17
				+M17x
				 M18
				 M19
				 M20""", render(before, after));
	}

	@Test
	@DisplayName("la sortie ne se termine jamais par un saut de ligne")
	void outputHasNoTrailingNewline() {
		final String diff = render(TEN_LINES, List.of("A", "B", "C", "D", "E2", "F", "G", "H", "I", "J"));

		assertTrue(diff.endsWith("\n") == false, "sortie terminée par un saut de ligne : <" + diff + ">");
		assertEquals(diff.stripTrailing(), diff);
	}

	@Test
	@DisplayName("une ligne vide reste une ligne de contexte, pas une ligne absente")
	void blankLinesAreRenderedAsContext() {
		// Une ligne vide inchangée doit sortir sous la forme d'un espace seul :
		// la manger produirait un diff qui ne se réapplique pas.
		final List<String> before = List.of("A", "", "C");
		final List<String> after = List.of("A", "", "C2");

		assertEquals("""
				--- before
				+++ after
				@@ -1,3 +1,3 @@
				 A
				\s
				-C
				+C2""", render(before, after));
	}

	private static String render(final List<String> before, final List<String> after) {
		return UnifiedDiff.render(before, after, "before", "after");
	}

	private static List<String> sixteenLines() {
		return numbered("L", 16);
	}

	private static List<String> twentyLines() {
		return numbered("M", 20);
	}

	private static List<String> numbered(final String prefix, final int count) {
		final List<String> lines = new ArrayList<>();
		for (int i = 1; i <= count; i++)
			lines.add(prefix + i);

		return List.copyOf(lines);
	}

	private static List<String> replace(final List<String> lines, final String from, final String to) {
		final List<String> result = new ArrayList<>();
		for (final String line : lines)
			result.add(line.equals(from) ? to : line);

		return List.copyOf(result);
	}

}
