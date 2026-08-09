package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de TransactionStack : la discipline de pile (quels id peuvent
 * s'ouvrir, dans quel ordre ils se ferment) et ce que commit/rollback font
 * réellement sur disque maintenant que chaque Transaction porte son propre
 * Snapshot d'ouverture - voir Transaction, dont TransactionTest couvre déjà
 * le détail par-fichier (beforeLines/restoreFile/modifiedFiles) ; ici,
 * l'accent est sur ce que la pile ajoute par-dessus : l'emboîtement.
 */
class TransactionStackTest {

	private TransactionStack stackOn(final Path root) {
		return new TransactionStack(new FilesRepository(root, new Md5Repository(root)));
	}

	// ------------------------------------------------------------------
	// Discipline de pile - ouverture, imbrication, fermeture
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un id à un seul segment s'ouvre sans transaction préalable")
	void aRootIdOpensWithNothingElseOpen(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);

		stack.open("$refactor_foo");

		assertEquals(List.of("$refactor_foo"), stack.openIds());
	}

	@Test
	@DisplayName("un id à plusieurs segments est refusé si rien n'est ouvert")
	void aMultiSegmentIdIsRejectedWithNothingOpen(@TempDir final Path root) {
		final TransactionStack stack = stackOn(root);

		assertThrows(IllegalArgumentException.class, () -> stack.open("$refactor_foo$part1"));
	}

	@Test
	@DisplayName("un sous-id qui étend exactement le sommet de pile est accepté")
	void aDirectSubIdIsAccepted(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");

		stack.open("$refactor_foo$part1");

		assertEquals(List.of("$refactor_foo", "$refactor_foo$part1"), stack.openIds());
	}

	@Test
	@DisplayName("un second sous-id non lié au sommet est refusé tant que le premier reste ouvert")
	void aSecondUnrelatedSubIdIsRejected(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");

		assertThrows(IllegalArgumentException.class, () -> stack.open("$refactor_foo$part2"));
	}

	@Test
	@DisplayName("un id déjà ouvert ailleurs dans le projet ne peut pas se rouvrir tel quel")
	void anIdAlreadyOpenCannotReopen(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");

		assertThrows(IllegalArgumentException.class, () -> stack.open("$refactor_foo"));
	}

	@Test
	@DisplayName("un id mal formé est refusé")
	void aMalformedIdIsRejected(@TempDir final Path root) {
		final TransactionStack stack = stackOn(root);

		assertThrows(IllegalArgumentException.class, () -> stack.open("refactor_foo"));
		assertThrows(IllegalArgumentException.class, () -> stack.open("$Refactor"));
	}

	@Test
	@DisplayName("commit()/rollback() sur un id inconnu est refusé")
	void unknownIdIsRejectedByCommitAndRollback(@TempDir final Path root) {
		final TransactionStack stack = stackOn(root);

		assertThrows(IllegalArgumentException.class, () -> stack.commit("$absent"));
		assertThrows(IllegalArgumentException.class, () -> stack.rollback("$absent"));
	}

	// ------------------------------------------------------------------
	// commit() : rien ne change sur disque, seul le point de restauration disparaît
	// ------------------------------------------------------------------

	@Test
	@DisplayName("commit() garde le fichier tel qu'il a été laissé - rien n'est ré-écrit")
	void commitKeepsTheFileAsLeft(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		write(root, "Foo.java", "class Foo { int i; }");

		stack.commit("$refactor_foo");

		assertEquals("class Foo { int i; }", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals(List.of(), stack.openIds());
	}

	@Test
	@DisplayName("commit() ferme aussi les sous-transactions encore ouvertes, sans toucher à leurs fichiers")
	void commitClosesStillOpenSubTransactions(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");
		write(root, "Foo.java", "class Foo { int i; }");

		stack.commit("$refactor_foo");

		assertEquals("class Foo { int i; }", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals(List.of(), stack.openIds());
	}

	@Test
	@DisplayName("committer une sous-transaction seule laisse le parent ouvert")
	void committingOnlyTheSubTransactionLeavesTheParentOpen(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");

		stack.commit("$refactor_foo$part1");

		assertEquals(List.of("$refactor_foo"), stack.openIds());
	}

	// ------------------------------------------------------------------
	// rollback() : un seul Snapshot suffit à défaire tout un sous-arbre
	// ------------------------------------------------------------------

	@Test
	@DisplayName("rollback() restaure le fichier tel qu'il était à l'ouverture")
	void rollbackRestoresTheOpeningContent(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		write(root, "Foo.java", "class Foo { int i; }");

		stack.rollback("$refactor_foo");

		assertEquals("class Foo {}", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals(List.of(), stack.openIds());
	}

	@Test
	@DisplayName("rollback() du parent défait aussi ce qu'une sous-transaction a changé - sans fusion explicite")
	void rollingBackTheParentUndoesTheSubTransactionToo(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");
		write(root, "Foo.java", "class Foo { int i; }");

		stack.rollback("$refactor_foo");

		assertEquals("class Foo {}", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals(List.of(), stack.openIds());
	}

	@Test
	@DisplayName("un changement commité sous une sous-transaction est quand même défait si le parent est ensuite roll-back")
	void aChangeCommittedUnderASubTransactionIsStillUndoneByTheParentsRollback(@TempDir final Path root)
			throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");
		write(root, "Foo.java", "class Foo { int i; }");
		stack.commit("$refactor_foo$part1");

		// part1 est fermée, son changement "gardé" - mais le parent, ouvert avant
		// elle, n'a jamais eu besoin qu'on lui transmette quoi que ce soit : son
		// propre Snapshot d'ouverture suffit à tout défaire.
		stack.rollback("$refactor_foo");

		assertEquals("class Foo {}", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("rollback() d'une sous-transaction seule laisse le parent ouvert et ses propres changements intacts")
	void rollingBackOnlyTheSubTransactionLeavesTheParentAlone(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		write(root, "Bar.java", "class Bar {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		write(root, "Foo.java", "class Foo { int i; }");
		stack.open("$refactor_foo$part1");
		write(root, "Bar.java", "class Bar { int j; }");

		stack.rollback("$refactor_foo$part1");

		assertEquals("class Foo { int i; }", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals("class Bar {}", Files.readString(root.resolve("Bar.java"), StandardCharsets.UTF_8));
		assertEquals(List.of("$refactor_foo"), stack.openIds());
	}

	// ------------------------------------------------------------------
	// beforeLines() / restoreFile() / modifiedFiles() - délégation directe à id
	// ------------------------------------------------------------------

	@Test
	@DisplayName("beforeLines() refuse un fichier jamais modifié sous cet id")
	void beforeLinesRejectsAnUntouchedFile(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");

		assertThrows(IllegalArgumentException.class, () -> stack.beforeLines("$refactor_foo", "Foo.java"));
	}

	@Test
	@DisplayName("restoreFile() ne touche que le fichier demandé, laisse la transaction ouverte")
	void restoreFileTouchesOnlyThatFile(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		write(root, "Bar.java", "class Bar {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		write(root, "Foo.java", "class Foo { int i; }");
		write(root, "Bar.java", "class Bar { int j; }");

		stack.restoreFile("$refactor_foo", "Foo.java");

		assertEquals("class Foo {}", Files.readString(root.resolve("Foo.java"), StandardCharsets.UTF_8));
		assertEquals("class Bar { int j; }", Files.readString(root.resolve("Bar.java"), StandardCharsets.UTF_8));
		assertEquals(List.of("$refactor_foo"), stack.openIds());
	}

	@Test
	@DisplayName("modifiedFiles() délègue directement à id, sans avoir besoin d'agréger les niveaux de la pile")
	void modifiedFilesDelegatesDirectlyToId(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.open("$refactor_foo$part1");
		write(root, "Foo.java", "class Foo { int i; }");

		assertEquals(List.of("Foo.java"), stack.modifiedFiles("$refactor_foo"));
		assertEquals(List.of("Foo.java"), stack.modifiedFiles("$refactor_foo$part1"));
	}

	@Test
	@DisplayName("currentLines() rend le contenu actuel, vide si le fichier n'existe pas")
	void currentLinesReadsTheLiveFile(@TempDir final Path root) throws IOException {
		write(root, "Foo.java", "class Foo {}");
		final TransactionStack stack = stackOn(root);

		assertEquals(List.of("class Foo {}"), stack.currentLines("Foo.java"));
		assertEquals(List.of(), stack.currentLines("Absent.java"));
	}

	// ------------------------------------------------------------------
	// refuseIfDirty() - la protection contre un crash en cours de transaction
	// ------------------------------------------------------------------

	@Test
	@DisplayName("refuseIfDirty() ne dit rien tant qu'aucune transaction n'a jamais été ouverte")
	void refuseIfDirtyIsSilentWithNothingEverOpened(@TempDir final Path root) throws IOException {
		TransactionStack.refuseIfDirty(root);
	}

	@Test
	@DisplayName("refuseIfDirty() ne dit rien une fois toutes les transactions proprement fermées")
	void refuseIfDirtyIsSilentOnceEverythingClosed(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		stack.commit("$refactor_foo");

		TransactionStack.refuseIfDirty(root);
	}

	@Test
	@DisplayName("refuseIfDirty() lève si une transaction reste ouverte - simule un crash")
	void refuseIfDirtyThrowsOnAStrandedTransaction(@TempDir final Path root) throws IOException {
		final TransactionStack stack = stackOn(root);
		stack.open("$refactor_foo");
		// pas de commit()/rollback() : simule un daemon mort avant la fermeture -
		// la nouvelle TransactionStack (ci-dessous) n'a plus le stack en mémoire,
		// seul le marqueur sur disque reste pour le détecter.

		assertThrows(IOException.class, () -> TransactionStack.refuseIfDirty(root));
	}

	private void write(final Path root, final String name, final String content) throws IOException {
		Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
	}

}
