package clide.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a source file back to disk the one way every command that edits
 * Java source in place - MoveClassCommand.addImportIfSafe(),
 * RemoveUnusedImportsCommand.removeUnusedImportLines() - needs it written:
 * lines joined by exactly '\n', on every platform clide runs on.
 *
 * {@code Files.write(Path, Iterable<CharSequence>, Charset)} looks like the
 * obvious tool for this and was what both commands used to call directly,
 * but it terminates every line with {@code System.lineSeparator()} - '\n' on
 * Linux/macOS, '\r\n' on Windows. A file a command "simply calls Files.write()"
 * on (see RemoveUnusedImportsCommand's own class doc) would then come back
 * CRLF-terminated on a Windows daemon even though every other file in the
 * project, and every fixture a test writes with Files.writeString(), is '\n'
 * - not a cosmetic difference: it silently rewrites every line of a file
 * clide was only supposed to touch once (add one import, delete one line),
 * which is exactly the kind of edit-something-unrelated bug the "smallest
 * edit that makes the file compile" philosophy those two commands document
 * exists to avoid.
 *
 * The fix is not "call Files.write() with a different line separator" -
 * there is no overload that takes one. It is to stop asking a
 * platform-dependent API to do this at all: join the lines by hand with the
 * one separator clide has ever produced or expected here, and write the
 * resulting text with Files.writeString(), which does not append a line
 * separator of its own.
 */
public final class SourceFiles {

	private SourceFiles() {
	}

	/**
	 * Writes lines to file, one per line, each terminated by '\n' - including
	 * the last one, matching what {@code Files.write(Path, Iterable, Charset)}
	 * would have written on a platform whose line.separator is already '\n'.
	 */
	public static void writeLines(final Path file, final List<String> lines) throws IOException {
		final StringBuilder content = new StringBuilder();
		for (final String line : lines)
			content.append(line).append('\n');

		Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
	}

}
