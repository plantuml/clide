package clide.command.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.CommandDispatcher;
import clide.core.FileChangeType;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.model.ChangedFile;

/**
 * Tests de snapshot / changed_since sur un vrai dossier.
 *
 * Ce qui compte ici est ce qui empêche de croire à tort qu'une référence n'a pas
 * bougé : le contenu seul décide (pas la date), un fichier créé après le
 * snapshot est vu, et le snapshot ne se déplace jamais tout seul.
 */
class SnapshotCommandsTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, new Md5Repository(root)), null, List.of());
	}

	private static CommandResult snapshot(final ClideContext context, final String name, final String glob) {
		return CommandDispatcher.dispatch(context, new SnapshotCommand(), notice -> {
		}, name, glob);
	}

	private static CommandResult changedSince(final ClideContext context, final String name) {
		return CommandDispatcher.dispatch(context, new ChangedSinceCommand(), notice -> {
		}, name);
	}

	private static void write(final Path file, final String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	/** Calculé ici plutôt qu'emprunté au code testé : c'est lui qui est éprouvé. */
	private static String md5(final String content) {
		try {
			final byte[] hash = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder();
			for (final byte b : hash)
				hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static CommandPayload.Changes changes(final CommandResult result) {
		assertFalse(result.isError(), result.message());
		return (CommandPayload.Changes) result.payload();
	}

	@Test
	@DisplayName("un snapshot compte les fichiers du glob, de n'importe quelle extension, à n'importe quelle profondeur")
	void snapshotCountsWhatTheGlobMatches(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "a");
		write(root.resolve("refs/deep/er/b.svg"), "b");
		write(root.resolve("refs/c.txt"), "c");
		write(root.resolve("top.svg"), "t");

		final CommandPayload.Snapshotted taken = (CommandPayload.Snapshotted) snapshot(contextOn(root), "refs",
				"refs/**.svg").payload();

		assertEquals(2, taken.fileCount());
		assertFalse(taken.replaced());
	}

	@Test
	@DisplayName("rien n'a bougé : liste vide")
	void nothingMoved(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "a");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");

		assertEquals(0, changes(changedSince(context, "s")).changes().totalCount());
	}

	@Test
	@DisplayName("créé, modifié et supprimé sont distingués, avec le md5 avant et après")
	void createdChangedDeleted(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/kept.svg"), "same");
		write(root.resolve("refs/edited.svg"), "old");
		write(root.resolve("refs/gone.svg"), "bye");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");

		write(root.resolve("refs/edited.svg"), "new");
		Files.delete(root.resolve("refs/gone.svg"));
		write(root.resolve("refs/sub/born.svg"), "hello");

		final List<ChangedFile> moved = changes(changedSince(context, "s")).changes().items();

		assertEquals(3, moved.size());
		assertEquals(List.of("refs/edited.svg", "refs/gone.svg", "refs/sub/born.svg"),
				moved.stream().map(ChangedFile::path).toList());

		final ChangedFile edited = moved.get(0);
		assertEquals(FileChangeType.CHANGED, edited.type());
		assertEquals(md5("old"), edited.md5Before());
		assertEquals(md5("new"), edited.md5After());

		assertEquals(FileChangeType.DELETED, moved.get(1).type());
		assertEquals("", moved.get(1).md5After());

		assertEquals(FileChangeType.CREATED, moved.get(2).type());
		assertEquals("", moved.get(2).md5Before());
	}

	@Test
	@DisplayName("un fichier réécrit à l'identique n'est pas un changement")
	void rewritingTheSameBytesIsNotAChange(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "same");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");

		write(root.resolve("refs/a.svg"), "same");

		assertEquals(0, changes(changedSince(context, "s")).changes().totalCount());
	}

	@Test
	@DisplayName("changed_since ne déplace pas le snapshot : deux questions, même réponse")
	void askingTwiceAnswersTheSame(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "a");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");
		write(root.resolve("refs/a.svg"), "b");

		assertEquals(1, changes(changedSince(context, "s")).changes().totalCount());
		assertEquals(1, changes(changedSince(context, "s")).changes().totalCount());
	}

	@Test
	@DisplayName("reprendre un snapshot sous le même nom remet le point de départ, et le dit")
	void retakingReplacesAndSaysSo(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "a");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");
		write(root.resolve("refs/a.svg"), "b");

		final CommandPayload.Snapshotted again = (CommandPayload.Snapshotted) snapshot(context, "s", "refs/**.svg")
				.payload();

		assertTrue(again.replaced());
		assertEquals(0, changes(changedSince(context, "s")).changes().totalCount());
	}

	@Test
	@DisplayName("deux snapshots de même nom de fichier dans des dossiers différents ne se confondent pas")
	void namesDoNotCollide(@TempDir final Path root) throws IOException {
		write(root.resolve("a/x.svg"), "1");
		write(root.resolve("b/x.svg"), "1");
		final ClideContext context = contextOn(root);
		snapshot(context, "both", "*/x.svg");

		write(root.resolve("b/x.svg"), "2");

		final List<ChangedFile> moved = changes(changedSince(context, "both")).changes().items();
		assertEquals(List.of("b/x.svg"), moved.stream().map(ChangedFile::path).toList());
	}

	@Test
	@DisplayName(".git et .clide ne sont jamais parcourus, même si le glob les couvre")
	void hiddenToolFoldersAreSkipped(@TempDir final Path root) throws IOException {
		write(root.resolve(".git/objects/x.svg"), "g");
		write(root.resolve(".clide/tmp/y.svg"), "c");
		write(root.resolve("z.svg"), "z");

		final CommandPayload.Snapshotted taken = (CommandPayload.Snapshotted) snapshot(contextOn(root), "s",
				"**.svg").payload();

		assertEquals(1, taken.fileCount());
	}

	@Test
	@DisplayName("un snapshot ne voit que ses fichiers : un autre fichier qui change ne compte pas")
	void onlyTheGlobIsWatched(@TempDir final Path root) throws IOException {
		write(root.resolve("refs/a.svg"), "a");
		write(root.resolve("other/b.txt"), "b");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");

		write(root.resolve("other/b.txt"), "changed");

		assertEquals(0, changes(changedSince(context, "s")).changes().totalCount());
	}

	@Test
	@DisplayName("un nom invalide, un glob invalide et un snapshot inconnu sont refusés")
	void refusals(@TempDir final Path root) {
		final ClideContext context = contextOn(root);

		assertEquals(ErrorCode.INVALID_SNAPSHOT_ID, snapshot(context, "a b", "**").code());
		assertEquals(ErrorCode.INVALID_SNAPSHOT_ID, snapshot(context, "x".repeat(65), "**").code());
		assertEquals(ErrorCode.INVALID_GLOB, snapshot(context, "ok", "refs/[").code());

		final CommandResult unknown = changedSince(context, "nobody");
		assertEquals(ErrorCode.NO_SUCH_SNAPSHOT, unknown.code());
		assertTrue(unknown.message().contains("none has been taken"), unknown.message());

		snapshot(context, "first", "**");
		assertTrue(changedSince(context, "nobody").message().contains("first"));
	}

	@Test
	@DisplayName("un snapshot sans aucun fichier n'est pas une erreur : c'est ainsi qu'on voit les fichiers apparaître")
	void emptySnapshotSeesCreations(@TempDir final Path root) throws IOException {
		final ClideContext context = contextOn(root);
		assertEquals(0, ((CommandPayload.Snapshotted) snapshot(context, "s", "refs/**.svg").payload()).fileCount());

		write(root.resolve("refs/new.svg"), "n");

		final List<ChangedFile> moved = changes(changedSince(context, "s")).changes().items();
		assertEquals(1, moved.size());
		assertEquals(FileChangeType.CREATED, moved.get(0).type());
	}

	@Test
	@DisplayName("suivre des .svg ne change rien à ce que jdtls se voit signaler : le scan des sources reste .java seul")
	void jdtlsScanIsUntouched(@TempDir final Path root) throws IOException {
		write(root.resolve("src/A.java"), "class A {}");
		write(root.resolve("refs/a.svg"), "a");
		final ClideContext context = contextOn(root);
		snapshot(context, "s", "**");

		write(root.resolve("refs/a.svg"), "changed");

		assertEquals(1, context.getFilesRepository().currentSourceFiles().size());
		assertTrue(context.getFilesRepository().currentSourceFiles().iterator().next().sourceFilePath()
				.endsWith("A.java"));
	}

	@Test
	@DisplayName("le plafond de résultats s'applique, le total reste exact")
	void listingIsCappedTotalIsExact(@TempDir final Path root) throws IOException {
		for (int i = 0; i < 5; i++)
			write(root.resolve("refs/f" + i + ".svg"), "a" + i);

		final ClideContext context = contextOn(root);
		snapshot(context, "s", "refs/**.svg");
		for (int i = 0; i < 5; i++)
			write(root.resolve("refs/f" + i + ".svg"), "b" + i);

		context.setMaxResults(2);
		final CommandPayload.Changes moved = changes(changedSince(context, "s"));

		assertEquals(5, moved.changes().totalCount());
		assertEquals(2, moved.changes().items().size());
		assertTrue(moved.changes().truncated());
	}

}
