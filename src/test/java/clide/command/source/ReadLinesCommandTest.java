package clide.command.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.CommandDispatcher;
import clide.core.FilesRepository;
import clide.model.SourceLine;

/** Tests de read_lines sur un vrai dossier : les bornes, et les refus qui comptent. */
class ReadLinesCommandTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	private static CommandResult read(final ClideContext context, final String path, final String from,
			final String to) {
		return CommandDispatcher.dispatch(context, new ReadLinesCommand(), notice -> {
		}, path, from, to);
	}

	private static Path fiveLines(final Path root) throws IOException {
		final Path file = root.resolve("src/A.java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
		return file;
	}

	private static List<String> texts(final CommandResult result) {
		return ((CommandPayload.Lines) result.payload()).lines().items().stream().map(SourceLine::text).toList();
	}

	@Test
	@DisplayName("une seule ligne, c'est read_lines f n n ; les deux bornes sont incluses")
	void oneLineAndInclusiveBounds(@TempDir final Path root) throws IOException {
		fiveLines(root);
		final ClideContext context = contextOn(root);

		assertEquals(List.of("three"), texts(read(context, "src/A.java", "3", "3")));
		assertEquals(List.of("two", "three", "four"), texts(read(context, "src/A.java", "2", "4")));
	}

	@Test
	@DisplayName("un <to> après la fin est coupé à la dernière ligne ; le nombre de lignes du fichier est rendu")
	void toPastTheEndIsCut(@TempDir final Path root) throws IOException {
		fiveLines(root);

		final CommandResult result = read(contextOn(root), "src/A.java", "4", "99");

		assertEquals(List.of("four", "five"), texts(result));
		final CommandPayload.Lines lines = (CommandPayload.Lines) result.payload();
		assertEquals("src/A.java", lines.path());
		assertEquals(5, lines.lineCount());
	}

	@Test
	@DisplayName("un <from> après la fin est refusé, en disant combien de lignes le fichier a")
	void fromPastTheEndIsRefused(@TempDir final Path root) throws IOException {
		fiveLines(root);

		final CommandResult result = read(contextOn(root), "src/A.java", "6", "6");

		assertTrue(result.isError());
		assertEquals(ErrorCode.LINE_OUT_OF_RANGE, result.code());
		assertTrue(result.message().contains("5 line(s)"), result.message());
	}

	@Test
	@DisplayName("<from> à 0 et <to> avant <from> sont refusés")
	void badRanges(@TempDir final Path root) throws IOException {
		fiveLines(root);
		final ClideContext context = contextOn(root);

		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, read(context, "src/A.java", "0", "2").code());
		assertEquals(ErrorCode.VALUE_OUT_OF_RANGE, read(context, "src/A.java", "4", "2").code());
	}

	@Test
	@DisplayName("un chemin qui sort du projet est refusé, même si le fichier existe")
	void pathOutsideTheProject(@TempDir final Path parent) throws IOException {
		Files.writeString(parent.resolve("secret.txt"), "x\n");
		final Path root = Files.createDirectories(parent.resolve("project"));
		final ClideContext context = contextOn(root);

		assertEquals(ErrorCode.PATH_OUTSIDE_PROJECT, read(context, "../secret.txt", "1", "1").code());
		assertEquals(ErrorCode.PATH_OUTSIDE_PROJECT,
				read(context, parent.resolve("secret.txt").toString(), "1", "1").code());
	}

	@Test
	@DisplayName("un fichier absent, un dossier, et un fichier qui n'est pas du texte UTF-8")
	void notAReadableFile(@TempDir final Path root) throws IOException {
		fiveLines(root);
		Files.write(root.resolve("blob.bin"), new byte[] { (byte) 0xff, (byte) 0xfe, (byte) 0xfd, '\n' });
		final ClideContext context = contextOn(root);

		assertEquals(ErrorCode.FILE_NOT_FOUND, read(context, "src/Missing.java", "1", "1").code());
		assertEquals(ErrorCode.FILE_NOT_FOUND, read(context, "src", "1", "1").code());
		assertEquals(ErrorCode.FILE_UNREADABLE, read(context, "blob.bin", "1", "1").code());
	}

	@Test
	@DisplayName("la liste est plafonnée par set_max_results, le total reste exact")
	void cappedButExact(@TempDir final Path root) throws IOException {
		fiveLines(root);
		final ClideContext context = contextOn(root);
		context.setMaxResults(2);

		final CommandPayload.Lines lines = (CommandPayload.Lines) read(context, "src/A.java", "1", "5").payload();

		assertEquals(List.of("one", "two"), lines.lines().items().stream().map(SourceLine::text).toList());
		assertEquals(5, lines.lines().totalCount());
		assertTrue(lines.lines().truncated());
	}

	@Test
	@DisplayName("ce qui n'est pas du .java se lit aussi, et le rendu texte est <chemin>:<ligne>: <texte>")
	void anyTextFileAndItsRendering(@TempDir final Path root) throws IOException {
		Files.writeString(root.resolve("notes.txt"), "a\nb\n");
		final ReadLinesCommand command = new ReadLinesCommand();
		final CommandResult result = read(contextOn(root), "notes.txt", "2", "2");

		assertFalse(result.isError());
		assertEquals("notes.txt:2: b\nread_lines: 1 line(s) of notes.txt (2 in the file)",
				command.render(result, clide.PrintMode.AI));
	}
}
