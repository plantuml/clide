package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SourceFiles.writeLines() exists to no longer depend on
 * System.lineSeparator() (see its own class doc for the CRLF-on-Windows bug
 * this replaced) - so what is worth testing here is exactly that: the bytes
 * written never carry a '\r', regardless of what the running platform's own
 * line.separator happens to be, not just that they happen to look right on
 * whichever platform runs this test.
 */
class SourceFilesTest {

	@Test
	@DisplayName("les lignes sont toujours jointes par '\\n', jamais par System.lineSeparator()")
	void linesAreAlwaysJoinedByLf(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Foo.java");

		SourceFiles.writeLines(file, List.of("package demo;", "", "class Foo {", "}"));

		final String written = Files.readString(file, StandardCharsets.UTF_8);
		assertEquals("package demo;\n\nclass Foo {\n}\n", written);
		assertFalse(written.contains("\r"), "no '\\r' should ever appear, on any platform: " + written);
	}

	@Test
	@DisplayName("une liste vide écrit un fichier vide")
	void emptyLinesWritesAnEmptyFile(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("Empty.java");

		SourceFiles.writeLines(file, List.of());

		assertEquals("", Files.readString(file, StandardCharsets.UTF_8));
	}

}
