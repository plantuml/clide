package clide.command.edit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.Md5Repository;
import clide.core.ModelSync;
import clide.core.PositionException;
import clide.core.PositionParser;
import clide.model.Listing;
import clide.model.Position;
import clide.model.SourceLine;

/**
 * Deletes whole lines, given by number, from one .java file - requires an open
 * transaction.
 *
 * <h2>Why not just edit the file</h2>
 *
 * Deleting a few lines of a big file is the edit an agent makes most often and
 * the one that is cheapest to get wrong: a line number read a moment ago points
 * somewhere else once anything above it moved. So the file is named the way a
 * &lt;position&gt; names it - with the optional &lt;file-content-md5&gt; in front
 * of the path - and a stale md5 is refused (FILE_MODIFIED) instead of deleting
 * whatever now sits on those line numbers. The answer carries the deleted lines
 * and the file's new md5, so the next edit can be chained without reading the
 * file again.
 *
 * <h2>All or nothing</h2>
 *
 * Every range is checked against the file before a single byte is written. A
 * range past the end of the file is refused, unlike read_lines, which cuts it:
 * "the next 5 lines" is a fair thing to read at the end of a file, but deleting
 * lines that do not exist means the caller's picture of the file is wrong, and
 * that is worth stopping for.
 *
 * <h2>Line terminators are left alone</h2>
 *
 * The file is cut as text, not read into lines and written back with
 * SourceFiles.writeLines(): that would rewrite every terminator of a CRLF file,
 * and add a final one to a file that had none. Here the terminator of each
 * surviving line is the one it already had.
 *
 * <h2>Writing needs no Transaction API call</h2>
 *
 * Same as RemoveUnusedImportsCommand: a transaction's opening Snapshot already
 * covers every .java file, so needsOpenTransaction() is all that is required of
 * this command for rollback_transaction and restore_file to undo it.
 *
 * <h2>No jdtls</h2>
 *
 * This is a text edit and asks jdtls nothing. It does tell the session, when
 * there is one, so that print_diagnostics is not left describing the file as it
 * was - but it does not rebuild: a deletion can break compilation anywhere, and
 * "rebuild" then "errors" is how a caller finds out.
 */
public class DeleteLinesCommand extends Command {

	/** Group 1: the optional signature, exactly Position.MD5_LENGTH lowercase hex characters. Group 2: the path. */
	private static final Pattern FILE = Pattern.compile("^(?:(" + Position.MD5_REGEX + "):)?(.+)$");

	/** One range: "12" or "12-15", no sign, no spaces. */
	private static final Pattern RANGE = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

	/** One line terminator, whichever of the three read_lines also recognises. */
	private static final Pattern TERMINATOR = Pattern.compile("\r\n|\n|\r");

	@Keyword("delete_lines")
	@Help("Deletes the lines <ranges> (1-based, e.g. 12-15,20) of the .java file <file> - requires an open transaction.")
	@Param(type = ParamType.SINGLE_LINE, description = "File")
	@Param(type = ParamType.SINGLE_LINE, description = "Ranges")
	@Manual("""
			NAME
				delete_lines - delete lines of a .java file, by number

			SYNOPSIS
				delete_lines <file> <ranges>

			DESCRIPTION
				Deletes the lines named by <ranges> from <file>.

				<file> is [<file-content-md5>:]<path>: the path of a .java
				file, relative to the project root, preceded by the signature
				of its content when the line numbers come from a previous
				answer - the first characters of the same md5 a <position>
				carries. When the signature is given and the file has changed
				since, nothing is deleted (FILE_MODIFIED): the line numbers no
				longer mean what they meant. Without it, the line numbers are
				taken as they are, on the file as it is now.

				<ranges> is a comma-separated list of line numbers and
				ranges, 1-based, both ends included, no spaces: 20 is one
				line, 12-15 is four, 12-15,20,30-32 is eight. They may come
				in any order but must not overlap. The numbers are those of
				the file before the deletion, however many ranges there are.

				Every range must lie inside the file: a line past the last one
				is refused, not cut. Nothing is written unless all of them are
				accepted.

				The answer lists the deleted lines as "<path>:<line>: <text>",
				numbered as they were before the deletion, then the number of
				lines the file has left and its new signature, which can be put
				in front of the path of the next delete_lines.

				The other lines are left exactly as they were, line terminators
				included. A file that ended without a terminator and loses its
				last line ends with its previous line's terminator.

				Nothing is rebuilt: a deletion can leave the project not
				compiling, and rebuild then print_diagnostics say where.

				Requires an open transaction: this writes a file, and a
				transaction is what undoes it. diff_transaction shows the
				change, restore_file takes back this file alone.

			ERRORS
				INVALID_LINE_RANGES - <ranges> is not a list of numbers and
				ranges, has a line 0, a range written backwards, or two
				ranges that overlap.

				LINE_OUT_OF_RANGE - a range goes past the last line of the
				file.

				FILE_MODIFIED - the signature in <file> is not the file's.

				FILE_NOT_FOUND, FILE_UNREADABLE, PATH_OUTSIDE_PROJECT - see
				read_lines.

				NOT_A_JAVA_FILE - <file> does not end in .java.

				NO_OPEN_TRANSACTION - see open_transaction.

			SEE ALSO
				read_lines(1), open_transaction(1), diff_transaction(1),
				restore_file(1), rebuild(1)
			""")
	public DeleteLinesCommand() {

	}

	/** Writes to the project - see the class doc. */
	@Override
	public boolean needsOpenTransaction() {
		return true;
	}

	/** Never asks jdtls anything - see the class doc. */
	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Matcher fileToken = FILE.matcher(params[0].strip());
		if (fileToken.matches() == false)
			return CommandResult.error(ErrorCode.FILE_NOT_FOUND, "No file given");

		final String signature = fileToken.group(1);
		final String pathArgument = fileToken.group(2);

		final List<int[]> ranges;
		try {
			ranges = parseRanges(params[1].strip());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.INVALID_LINE_RANGES, e.getMessage());
		}

		final Path root = context.getProjectRoot().normalize();
		final Path file;
		try {
			file = PositionParser.resolvePath(pathArgument, root);
		} catch (final PositionException e) {
			return CommandResult.error(e.getCode(), e.getMessage());
		}
		if (file.startsWith(root) == false)
			return CommandResult.error(ErrorCode.PATH_OUTSIDE_PROJECT,
					"'" + pathArgument + "' is outside the project root " + root);

		if (Files.isRegularFile(file) == false)
			return CommandResult.error(ErrorCode.FILE_NOT_FOUND,
					"Not a file: '" + pathArgument + "' (resolved against the project root " + root + ")");

		if (file.getFileName().toString().endsWith(".java") == false)
			return CommandResult.error(ErrorCode.NOT_A_JAVA_FILE, "Not a .java file: '" + pathArgument + "'");

		final String content;
		try {
			// A strict decoder, like readAllLines(): a file that is not UTF-8 is refused,
			// never rewritten with its unreadable bytes replaced.
			content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file)))
					.toString();
			final String current = Position.abbreviate(Md5Repository.md5Of(file));
			if (signature != null && signature.equals(current) == false)
				return CommandResult.error(ErrorCode.FILE_MODIFIED, "Stale file: " + pathArgument
						+ " has changed since this signature was produced - its content no longer signs as "
						+ signature);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.FILE_UNREADABLE,
					"Could not read " + pathArgument + ": " + e.getMessage());
		}

		final List<String> pieces = splitKeepingTerminators(content);
		final int lineCount = pieces.size();
		for (final int[] range : ranges)
			if (range[1] > lineCount)
				return CommandResult.error(ErrorCode.LINE_OUT_OF_RANGE, "Line " + range[1]
						+ " out of range (file has " + lineCount + " line(s)): " + pathArgument);

		final List<SourceLine> deleted = new ArrayList<>();
		final StringBuilder kept = new StringBuilder();
		int range = 0;
		for (int line = 1; line <= lineCount; line++) {
			while (range < ranges.size() && ranges.get(range)[1] < line)
				range++;

			final String piece = pieces.get(line - 1);
			if (range < ranges.size() && ranges.get(range)[0] <= line)
				deleted.add(new SourceLine(line, withoutTerminator(piece)));
			else
				kept.append(piece);
		}

		final String md5;
		try {
			Files.writeString(file, kept.toString(), StandardCharsets.UTF_8);
			md5 = Position.abbreviate(Md5Repository.md5Of(file));
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED,
					"delete_lines failed on '" + pathArgument + "': " + e.getMessage());
		}

		// Best-effort, whatever its name: the same "tell jdtls about a file clide
		// just wrote" that restore_file does. The next command to question jdtls
		// resynchronises anyway.
		ModelSync.afterRestore(context);

		final String path = root.relativize(file).toString().replace('\\', '/');
		return CommandResult.ok(new CommandPayload.LinesDeleted(path, md5, lineCount - deleted.size(),
				Listing.of(deleted, context.getMaxResults())));
	}

	/**
	 * "12-15,20,30-32" as [from, to] pairs, both included, sorted by from.
	 * Package-private: it needs neither a project nor a file to be tested.
	 *
	 * @throws IllegalArgumentException with the message the caller is shown
	 */
	static List<int[]> parseRanges(final String text) {
		if (text.isEmpty())
			throw new IllegalArgumentException("No line ranges given - expected e.g. 12-15,20");

		final List<int[]> ranges = new ArrayList<>();
		for (final String part : text.split(",", -1)) {
			final Matcher matcher = RANGE.matcher(part);
			if (matcher.matches() == false)
				throw new IllegalArgumentException(
						"Invalid line range '" + part + "' - expected <n> or <from>-<to>, comma-separated");

			final int from;
			final int to;
			try {
				from = Integer.parseInt(matcher.group(1));
				to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
			} catch (final NumberFormatException e) {
				throw new IllegalArgumentException("Line number too large in '" + part + "'");
			}
			if (from < 1)
				throw new IllegalArgumentException("Lines are 1-based: got " + part);

			if (to < from)
				throw new IllegalArgumentException("Range written backwards: " + part);

			ranges.add(new int[] { from, to });
		}

		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int i = 1; i < ranges.size(); i++)
			if (ranges.get(i)[0] <= ranges.get(i - 1)[1])
				throw new IllegalArgumentException("Overlapping ranges: " + format(ranges.get(i - 1)) + " and "
						+ format(ranges.get(i)));

		return ranges;
	}

	private static String format(final int[] range) {
		return range[0] == range[1] ? String.valueOf(range[0]) : range[0] + "-" + range[1];
	}

	/**
	 * content cut into lines, each keeping its own terminator; the last one has
	 * none when the file does not end with one. Counts lines exactly as
	 * Files.readAllLines() does, which is what read_lines and every other line
	 * number in clide are based on.
	 */
	static List<String> splitKeepingTerminators(final String content) {
		final List<String> pieces = new ArrayList<>();
		final Matcher terminator = TERMINATOR.matcher(content);
		int start = 0;
		while (terminator.find()) {
			pieces.add(content.substring(start, terminator.end()));
			start = terminator.end();
		}
		if (start < content.length())
			pieces.add(content.substring(start));

		return pieces;
	}

	private static String withoutTerminator(final String piece) {
		final Matcher terminator = TERMINATOR.matcher(piece);
		return terminator.find() ? piece.substring(0, terminator.start()) : piece;
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.LinesDeleted done -> {
			final StringBuilder out = new StringBuilder();
			for (final SourceLine line : done.deleted().items())
				out.append(done.path()).append(':').append(line.line()).append(": ").append(line.text()).append('\n');

			out.append("delete_lines: ").append(done.deleted().summarize("line")).append(" deleted from ")
					.append(done.path()).append(" (").append(done.lineCount()).append(" left, now ")
					.append(done.md5()).append(':').append(done.path()).append(')');
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
