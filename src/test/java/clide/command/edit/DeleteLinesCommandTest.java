package clide.command.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.CommandDispatcher;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.model.Position;
import clide.model.SourceLine;

/**
 * Tests de delete_lines sur un vrai dossier, sans jdtls : les plages, le
 * tout-ou-rien, la signature qui protège des numéros périmés, et surtout que
 * les lignes qui restent ne bougent pas d'un octet (terminateurs compris).
 */
class DeleteLinesCommandTest {

	private static ClideContext openContextOn(final Path root) throws IOException {
		final ClideContext context = new ClideContext(new FilesRepository(root, new Md5Repository(root)), null,
				List.of());
		context.getTransactions().open("$t");
		return context;
	}

	private static CommandResult delete(final ClideContext context, final String file, final String ranges) {
		return CommandDispatcher.dispatch(context, new DeleteLinesCommand(), notice -> {
		}, file, ranges);
	}

	private static Path write(final Path root, final String content) throws IOException {
		final Path file = root.resolve("src/A.java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private static String read(final Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	private static CommandPayload.LinesDeleted payload(final CommandResult result) {
		return (CommandPayload.LinesDeleted) result.payload();
	}

	private static List<String> ranges(final String text) {
		return DeleteLinesCommand.parseRanges(text).stream().map(r -> r[0] + "-" + r[1]).toList();
	}

	// ------------------------------------------------------------------
	// Les plages
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un numéro seul, une plage, un mélange : triés, bornes incluses")
	void rangesAreParsed() {
		assertEquals(List.of("3-3"), ranges("3"));
		assertEquals(List.of("2-4"), ranges("2-4"));
		assertEquals(List.of("2-4", "7-7", "9-10"), ranges("9-10,2-4,7"));
		assertEquals(List.of("2-3", "4-5"), ranges("2-3,4-5"));
	}

	@Test
	@DisplayName("vide, 0, à l'envers, mal écrit, chevauchant : refusés")
	void badRangesAreRefused() {
		for (final String bad : List.of("", "0", "0-3", "5-2", "a", "1,,2", "1-", "-3", "1 - 3", "1,2,", "3-5,4",
				"3,3", "2-6,4-8", "99999999999"))
			assertThrows(IllegalArgumentException.class, () -> DeleteLinesCommand.parseRanges(bad), "'" + bad + "'");
	}

	@Test
	@DisplayName("un chevauchement dit lesquelles des plages se chevauchent")
	void overlapNamesTheRanges() {
		final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> DeleteLinesCommand.parseRanges("12-15,14"));

		assertTrue(e.getMessage().contains("12-15") && e.getMessage().contains("14"), e.getMessage());
	}

	// ------------------------------------------------------------------
	// L'effet sur le fichier
	// ------------------------------------------------------------------

	@Test
	@DisplayName("plusieurs plages en un appel, numérotées comme avant la suppression")
	void severalRangesInOneCall(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\nthree\nfour\nfive\nsix\nseven\n");

		final CommandResult result = delete(openContextOn(root), "src/A.java", "6-7,2,4");

		assertTrue(result.isError() == false, result.message());
		assertEquals("one\nthree\nfive\n", read(file));
		final CommandPayload.LinesDeleted done = payload(result);
		assertEquals("src/A.java", done.path());
		assertEquals(3, done.lineCount());
		assertEquals(List.of(new SourceLine(2, "two"), new SourceLine(4, "four"), new SourceLine(6, "six"),
				new SourceLine(7, "seven")), done.deleted().items());
	}

	@Test
	@DisplayName("les terminateurs des lignes qui restent ne changent pas : un fichier CRLF reste CRLF")
	void terminatorsAreKept(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\r\ntwo\r\nthree\nfour\r\n");

		final CommandResult result = delete(openContextOn(root), "src/A.java", "2");

		assertEquals("one\r\nthree\nfour\r\n", read(file));
		assertEquals("two", payload(result).deleted().items().get(0).text());
	}

	@Test
	@DisplayName("sans terminateur final, le fichier n'en gagne pas quand on supprime une autre ligne")
	void noFinalTerminatorIsKept(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\nthree");

		delete(openContextOn(root), "src/A.java", "1");

		assertEquals("two\nthree", read(file));
	}

	@Test
	@DisplayName("supprimer toutes les lignes laisse un fichier vide")
	void everythingCanGo(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\n");

		final CommandResult result = delete(openContextOn(root), "src/A.java", "1-2");

		assertEquals("", read(file));
		assertEquals(0, payload(result).lineCount());
	}

	@Test
	@DisplayName("la nouvelle signature rendue est celle du fichier tel qu'il est maintenant")
	void newSignatureIsTheFilesOwn(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\nthree\n");

		final CommandResult result = delete(openContextOn(root), "src/A.java", "2");

		assertEquals(Position.abbreviate(Md5Repository.md5Of(file)), payload(result).md5());
	}

	@Test
	@DisplayName("la signature rendue permet l'appel suivant, et une signature périmée est refusée")
	void signatureChainsAndGuards(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\nthree\nfour\n");
		final String before = Position.abbreviate(Md5Repository.md5Of(file));
		final ClideContext context = openContextOn(root);

		final CommandResult first = delete(context, before + ":src/A.java", "1");
		assertTrue(first.isError() == false, first.message());
		assertEquals("two\nthree\nfour\n", read(file));

		final CommandResult stale = delete(context, before + ":src/A.java", "1");
		assertEquals(ErrorCode.FILE_MODIFIED, stale.code());
		assertEquals("two\nthree\nfour\n", read(file));

		final CommandResult chained = delete(context, payload(first).md5() + ":src/A.java", "1");
		assertTrue(chained.isError() == false, chained.message());
		assertEquals("three\nfour\n", read(file));
	}

	// ------------------------------------------------------------------
	// Les refus : rien n'est écrit
	// ------------------------------------------------------------------

	@Test
	@DisplayName("une plage après la fin refuse tout, même les plages valides ; le fichier est intact")
	void pastTheEndWritesNothing(@TempDir final Path root) throws IOException {
		final String content = "one\ntwo\nthree\n";
		final Path file = write(root, content);

		final CommandResult result = delete(openContextOn(root), "src/A.java", "1,3-4");

		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, result.code());
		assertTrue(result.message().contains("3 line"), result.message());
		assertEquals(content, read(file));
	}

	@Test
	@DisplayName("des plages invalides sont INVALID_LINE_RANGES")
	void invalidRangesCode(@TempDir final Path root) throws IOException {
		write(root, "one\n");

		assertEquals(ErrorCode.INVALID_LINE_RANGES, delete(openContextOn(root), "src/A.java", "2-1").code());
	}

	@Test
	@DisplayName("sans transaction ouverte, la commande est refusée")
	void needsATransaction(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\n");
		final ClideContext context = new ClideContext(new FilesRepository(root, new Md5Repository(root)), null,
				List.of());

		assertEquals(ErrorCode.NO_OPEN_TRANSACTION, delete(context, "src/A.java", "1").code());
		assertEquals("one\ntwo\n", read(file));
	}

	@Test
	@DisplayName("un fichier qui n'est pas .java, qui n'existe pas ou qui sort du projet est refusé")
	void wrongFiles(@TempDir final Path root) throws IOException {
		write(root, "one\n");
		Files.writeString(root.resolve("notes.txt"), "one\n");
		final ClideContext context = openContextOn(root);

		assertEquals(ErrorCode.NOT_A_JAVA_FILE, delete(context, "notes.txt", "1").code());
		assertEquals(ErrorCode.FILE_NOT_FOUND, delete(context, "src/B.java", "1").code());
		assertEquals(ErrorCode.PATH_OUTSIDE_PROJECT, delete(context, "../A.java", "1").code());
	}

	@Test
	@DisplayName("un fichier qui n'est pas de l'UTF-8 est refusé, pas réécrit avec des octets remplacés")
	void notUtf8IsRefused(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("src/A.java");
		Files.createDirectories(file.getParent());
		final byte[] latin1 = { 'a', '\n', (byte) 0xE9, '\n' };
		Files.write(file, latin1);

		final CommandResult result = delete(openContextOn(root), "src/A.java", "1");

		assertEquals(ErrorCode.FILE_UNREADABLE, result.code());
		assertTrue(java.util.Arrays.equals(latin1, Files.readAllBytes(file)));
	}

	// ------------------------------------------------------------------
	// Le rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("le rendu liste les lignes supprimées avec leur ancien numéro, puis le bilan et la signature")
	void rendering(@TempDir final Path root) throws IOException {
		final Path file = write(root, "one\ntwo\nthree\n");
		final DeleteLinesCommand command = new DeleteLinesCommand();
		final CommandResult result = delete(openContextOn(root), "src/A.java", "2-3");

		final String text = command.render(result, PrintMode.AI);

		assertEquals("src/A.java:2: two\nsrc/A.java:3: three\n"
				+ "delete_lines: 2 line(s) deleted from src/A.java (1 left, now "
				+ Position.abbreviate(Md5Repository.md5Of(file)) + ":src/A.java)", text);
	}

}
