package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de Snapshot.compareWithPreviousSnapshot() - le diff de deux
 * instantanés, rendu sous forme de Delta. C'est le seul calcul de diff du
 * projet : fileEventsTo() s'y ramène (voir SnapshotTest).
 *
 * Comme pour SnapshotTest, tout est purement système de fichiers : aucun jdtls
 * ici, seulement de vrais répertoires temporaires.
 *
 * L'attention porte surtout sur les deux cas qui justifient de comparer le
 * contenu (md5) plutôt que la date de modification :
 *
 * - un fichier réécrit à l'identique, mtime bougé, octets inchangés : rien à
 * signaler, alors qu'une comparaison par mtime réclamerait une recompilation
 * pour rien ;
 *
 * - un fichier dont le contenu change sans que le mtime bouge : CHANGED, alors
 * qu'une comparaison par mtime ne verrait rien du tout.
 *
 * Le second n'est pas un cas d'école : sur un système de fichiers dont la
 * granularité est la seconde, deux écritures rapprochées portent le même
 * horodatage, et c'est exactement le fichier qu'on vient d'éditer qui passerait
 * inaperçu. Les mtimes sont donc posés explicitement avec
 * setLastModifiedTime() dans ces deux tests - non pas parce qu'ils comptent,
 * mais pour prouver qu'ils ne comptent pas.
 *
 * L'ordre des arguments est vérifié à part (deltaIsReadFromTheRecentSnapshot) :
 * le receveur est l'instantané récent et l'argument le précédent, soit
 * l'inverse de fileEventsTo(). Intervertir les deux compile parfaitement et
 * n'échange que CREATED et DELETED.
 */
class SnapshotDeltaTest {

	@Test
	@DisplayName("deux instantanés identiques produisent un delta vide")
	void identicalSnapshotsProduceAnEmptyDelta(@TempDir final Path projectRoot) throws IOException {
		writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());

		final Snapshot before = Snapshot.build(repository);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.empty(), after.compareWithPreviousSnapshot(before));
		assertTrue(after.compareWithPreviousSnapshot(before).isEmpty());
	}

	@Test
	@DisplayName("un fichier apparu est CREATED")
	void aNewFileIsCreated(@TempDir final Path projectRoot) throws IOException {
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		final Path added = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(added.toString(), FileChangeType.CREATED)),
				after.compareWithPreviousSnapshot(before));
	}

	@Test
	@DisplayName("un fichier dont le contenu a changé est CHANGED")
	void anEditedFileIsChanged(@TempDir final Path projectRoot) throws IOException {
		final Path edited = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Alpha.java", "class Alpha { void nouveau() {} }", 2_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(edited.toString(), FileChangeType.CHANGED)),
				after.compareWithPreviousSnapshot(before));
	}

	@Test
	@DisplayName("un fichier disparu est DELETED")
	void aRemovedFileIsDeleted(@TempDir final Path projectRoot) throws IOException {
		final Path removed = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		Files.delete(removed);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(removed.toString(), FileChangeType.DELETED)),
				after.compareWithPreviousSnapshot(before));
	}

	@Test
	@DisplayName("un fichier réécrit à l'identique ne bouge pas, alors que son mtime a bougé")
	void aRewrittenButIdenticalFileDoesNotMove(@TempDir final Path projectRoot) throws IOException {
		writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Alpha.java", "class Alpha {}", 2_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.empty(), after.compareWithPreviousSnapshot(before));

		// Et rien non plus par l'ancienne porte d'entrée, qui se ramène au même
		// diff : jdtls n'est pas prévenu, donc ne recompile pas pour rien.
		assertTrue(after.compareWithPreviousSnapshot(before).fileEvents().isEmpty());
	}

	@Test
	@DisplayName("un fichier modifié sans que son mtime bouge est quand même CHANGED")
	void anEditedFileWithAnUnchangedMtimeIsStillChanged(@TempDir final Path projectRoot) throws IOException {
		final Path edited = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Alpha.java", "class Alpha { void nouveau() {} }", 1_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(edited.toString(), FileChangeType.CHANGED)),
				after.compareWithPreviousSnapshot(before));

		// Et le CHANGED(2) ressort bien par l'ancienne porte d'entrée aussi :
		// c'est précisément le fichier qu'une comparaison par mtime laisserait
		// passer.
		assertEquals(1, after.compareWithPreviousSnapshot(before).fileEvents().size());
		assertEquals(2, after.compareWithPreviousSnapshot(before).fileEvents().get(0).getFromMap("type").asInt());
	}

	@Test
	@DisplayName("les trois branches se cumulent dans un même delta, et un fichier intact n'y figure pas")
	void allThreeBranchesInOneDelta(@TempDir final Path projectRoot) throws IOException {
		writeSource(projectRoot, "Untouched.java", "class Untouched {}", 1_000_000);
		final Path edited = writeSource(projectRoot, "Edited.java", "class Edited {}", 1_000_000);
		final Path removed = writeSource(projectRoot, "Removed.java", "class Removed {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Edited.java", "class Edited { int i; }", 2_000_000);
		Files.delete(removed);
		final Path added = writeSource(projectRoot, "Added.java", "class Added {}", 1_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(
				Delta.of(new FileChange(added.toString(), FileChangeType.CREATED),
						new FileChange(edited.toString(), FileChangeType.CHANGED),
						new FileChange(removed.toString(), FileChangeType.DELETED)),
				after.compareWithPreviousSnapshot(before));
	}

	@Test
	@DisplayName("contre empty(), tout fichier existant est CREATED")
	void everythingIsCreatedAgainstEmpty(@TempDir final Path projectRoot) throws IOException {
		final Path first = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final Path second = writeSource(projectRoot, "Beta.java", "class Beta {}", 1_000_000);

		final Snapshot after = Snapshot.build(new FilesRepository(projectRoot, Md5Repository.none()));

		assertEquals(Delta.of(new FileChange(first.toString(), FileChangeType.CREATED),
				new FileChange(second.toString(), FileChangeType.CREATED)),
				after.compareWithPreviousSnapshot(Snapshot.empty()));
	}

	@Test
	@DisplayName("empty() contre un instantané peuplé : tout est DELETED")
	void everythingIsDeletedWhenTheRecentSnapshotIsEmpty(@TempDir final Path projectRoot) throws IOException {
		final Path only = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final Snapshot populated = Snapshot.build(new FilesRepository(projectRoot, Md5Repository.none()));

		assertEquals(Delta.of(new FileChange(only.toString(), FileChangeType.DELETED)),
				Snapshot.empty().compareWithPreviousSnapshot(populated));
	}

	@Test
	@DisplayName("le receveur est l'instantané récent, l'argument le précédent - les intervertir inverse tout")
	void deltaIsReadFromTheRecentSnapshot(@TempDir final Path projectRoot) throws IOException {
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);
		final Path added = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(added.toString(), FileChangeType.CREATED)),
				after.compareWithPreviousSnapshot(before));
		assertEquals(Delta.of(new FileChange(added.toString(), FileChangeType.DELETED)),
				before.compareWithPreviousSnapshot(after));
	}

	@Test
	@DisplayName("un fichier déplacé est un DELETED et un CREATED, pas un renommage")
	void aMovedFileIsDeletedAndCreated(@TempDir final Path projectRoot) throws IOException {
		final Path source = writeSource(projectRoot, "Alpha.java", "class Alpha {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		final Path destination = Files.createDirectory(projectRoot.resolve("sous-dossier")).resolve("Alpha.java");
		Files.move(source, destination);
		Files.setLastModifiedTime(destination, FileTime.fromMillis(1_000_000));
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Delta.of(new FileChange(destination.toString(), FileChangeType.CREATED),
				new FileChange(source.toString(), FileChangeType.DELETED)),
				after.compareWithPreviousSnapshot(before));
	}

	@Test
	@DisplayName("un fichier qui n'est pas un .java est ignoré des deux côtés du delta")
	void nonJavaFilesAreIgnored(@TempDir final Path projectRoot) throws IOException {
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		Files.writeString(projectRoot.resolve("notes.txt"), "rien à compiler", StandardCharsets.UTF_8);
		final Snapshot after = Snapshot.build(repository);

		assertTrue(after.compareWithPreviousSnapshot(before).isEmpty());
	}

	@Test
	@DisplayName("le delta se traduit en événements LSP, un par fichier")
	void theDeltaTranslatesToLspEvents(@TempDir final Path projectRoot) throws IOException {
		final Path removed = writeSource(projectRoot, "Removed.java", "class Removed {}", 1_000_000);
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		Files.delete(removed);
		final Path added = writeSource(projectRoot, "Added.java", "class Added {}", 1_000_000);
		final Snapshot after = Snapshot.build(repository);

		final List<Monomorphic> events = after.compareWithPreviousSnapshot(before).fileEvents();

		assertEquals(2, events.size());
		assertEquals(added.toUri().toString(), events.get(0).getFromMap("uri").asString());
		assertEquals(1, events.get(0).getFromMap("type").asInt());
		assertEquals(removed.toUri().toString(), events.get(1).getFromMap("uri").asString());
		assertEquals(3, events.get(1).getFromMap("type").asInt());
	}

	@Test
	@DisplayName("comparer avec null est refusé")
	void comparingWithNullIsRefused(@TempDir final Path projectRoot) throws IOException {
		final Snapshot snapshot = Snapshot.build(new FilesRepository(projectRoot, Md5Repository.none()));

		assertThrows(NullPointerException.class, () -> snapshot.compareWithPreviousSnapshot(null));
	}

	private Path writeSource(final Path projectRoot, final String name, final String content, final long mtime)
			throws IOException {
		final Path file = projectRoot.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
		return file;
	}

}
