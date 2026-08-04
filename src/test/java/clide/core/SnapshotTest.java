package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de Snapshot.fileEventsTo() - la comparaison de deux instantanés est
 * purement système de fichiers, aucun jdtls ici, donc entièrement testable avec
 * de vrais répertoires temporaires.
 *
 * Les trois branches (créé, modifié, supprimé) ne sont pas vérifiées seulement
 * par leur nombre mais par le contenu exact des événements LSP produits : le
 * chemin, sous forme d'URI, et surtout le code numérique du type. Ces codes
 * (1, 2, 3) sont imposés par le protocole LSP, pas par nous - une inversion
 * entre CREATED et DELETED compilerait sans broncher et ne se verrait qu'à
 * l'exécution, sur un diagnostic manquant ou fantôme.
 *
 * fileEventsTo() ne calcule plus le diff elle-même : elle délègue à
 * compareWithPreviousSnapshot(), en lisant le résultat depuis l'instantané le
 * plus ancien plutôt que le plus récent. Ce sont donc bien ces deux choses que
 * ces tests couvrent - le sens de lecture et la traduction en LSP ; le diff
 * lui-même est éprouvé dans SnapshotDeltaTest.
 *
 * Ce qui déclenche un CHANGED est le contenu du fichier, plus sa date de
 * modification : les tests réécrivent donc les sources, et ne touchent jamais
 * les mtimes. Un simple touch ne produit plus rien, exprès - c'est un cas à
 * part entière dans SnapshotDeltaTest.
 */
class SnapshotTest {

	private static final int CREATED = 1;
	private static final int CHANGED = 2;
	private static final int DELETED = 3;

	@Test
	@DisplayName("deux instantanés identiques ne produisent aucun événement")
	void identicalSnapshotsProduceNothing(@TempDir final Path projectRoot) throws IOException {
		writeSource(projectRoot, "Alpha.java", "class Alpha {}");
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());

		final Snapshot before = Snapshot.build(repository);
		final Snapshot after = Snapshot.build(repository);

		assertTrue(after.compareWithPreviousSnapshot(before).fileEvents().isEmpty());
	}

	@Test
	@DisplayName("un fichier apparu est Created(1)")
	void aNewFileIsCreated(@TempDir final Path projectRoot) throws IOException {
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		final Path added = writeSource(projectRoot, "Alpha.java", "class Alpha {}");
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Map.of(uriOf(added), CREATED), eventsOf(after.compareWithPreviousSnapshot(before).fileEvents()));
	}

	@Test
	@DisplayName("un fichier dont le contenu a bougé est Changed(2)")
	void anEditedFileIsChanged(@TempDir final Path projectRoot) throws IOException {
		final Path edited = writeSource(projectRoot, "Alpha.java", "class Alpha {}");
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Alpha.java", "class Alpha { int i; }");
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Map.of(uriOf(edited), CHANGED), eventsOf(after.compareWithPreviousSnapshot(before).fileEvents()));
	}

	@Test
	@DisplayName("un fichier disparu est Deleted(3)")
	void aRemovedFileIsDeleted(@TempDir final Path projectRoot) throws IOException {
		final Path removed = writeSource(projectRoot, "Alpha.java", "class Alpha {}");
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		Files.delete(removed);
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Map.of(uriOf(removed), DELETED), eventsOf(after.compareWithPreviousSnapshot(before).fileEvents()));
	}

	@Test
	@DisplayName("les trois branches se cumulent dans un même diff, et un fichier intact n'y figure pas")
	void allThreeBranchesInOneDiff(@TempDir final Path projectRoot) throws IOException {
		final Path untouched = writeSource(projectRoot, "Untouched.java", "class Untouched {}");
		final Path edited = writeSource(projectRoot, "Edited.java", "class Edited {}");
		final Path removed = writeSource(projectRoot, "Removed.java", "class Removed {}");
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		writeSource(projectRoot, "Edited.java", "class Edited { int i; }");
		Files.delete(removed);
		final Path added = writeSource(projectRoot, "Added.java", "class Added {}");
		final Snapshot after = Snapshot.build(repository);

		assertEquals(Map.of(uriOf(added), CREATED, uriOf(edited), CHANGED, uriOf(removed), DELETED),
				eventsOf(after.compareWithPreviousSnapshot(before).fileEvents()));
		assertTrue(eventsOf(after.compareWithPreviousSnapshot(before).fileEvents()).containsKey(uriOf(untouched)) == false);
	}

	@Test
	@DisplayName("contre empty(), tout fichier existant est Created(1)")
	void everythingIsCreatedAgainstEmpty(@TempDir final Path projectRoot) throws IOException {
		final Path first = writeSource(projectRoot, "Alpha.java", "class Alpha {}");
		final Path second = writeSource(projectRoot, "Beta.java", "class Beta {}");

		final Snapshot after = Snapshot.build(new FilesRepository(projectRoot, Md5Repository.none()));

		assertEquals(Map.of(uriOf(first), CREATED, uriOf(second), CREATED),
				eventsOf(after.compareWithPreviousSnapshot(Snapshot.empty()).fileEvents()));
	}

	@Test
	@DisplayName("un fichier qui n'est pas un .java est ignoré des deux côtés du diff")
	void nonJavaFilesAreIgnored(@TempDir final Path projectRoot) throws IOException {
		final FilesRepository repository = new FilesRepository(projectRoot, Md5Repository.none());
		final Snapshot before = Snapshot.build(repository);

		Files.writeString(projectRoot.resolve("notes.txt"), "rien à compiler", StandardCharsets.UTF_8);
		final Snapshot after = Snapshot.build(repository);

		assertTrue(after.compareWithPreviousSnapshot(before).fileEvents().isEmpty());
	}

	/**
	 * Les événements réduits à "uri -&gt; type", trié, pour être comparés d'un
	 * bloc : l'ordre dans lequel ils sortent est celui des chemins, mais rien de
	 * ce qui est vérifié ici n'en dépend.
	 */
	private Map<String, Integer> eventsOf(final List<Monomorphic> events) {
		final Map<String, Integer> byUri = new TreeMap<>();
		for (final Monomorphic event : events)
			byUri.put(event.getFromMap("uri").asString(), event.getFromMap("type").asInt());

		return byUri;
	}

	private Path writeSource(final Path projectRoot, final String name, final String content) throws IOException {
		final Path file = projectRoot.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private String uriOf(final Path file) {
		return file.toUri().toString();
	}
}
