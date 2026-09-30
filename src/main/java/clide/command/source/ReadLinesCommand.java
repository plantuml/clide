package clide.command.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import clide.core.PositionException;
import clide.core.PositionParser;
import clide.model.Listing;
import clide.model.SourceLine;

/**
 * The lines <from>..<to> of a file - the one way for a script to see what
 * search_regex's single matching line does not show: what surrounds it.
 *
 * Plain filesystem read, never jdtls. The three parameters are all required:
 * clide has no optional argument, and "just line n" is read_lines f n n.
 */
public class ReadLinesCommand extends Command {

	@Keyword("read_lines")
	@Help("Reads the lines <from> to <to> (1-based, both included) of the file <path>.")
	@Param(type = ParamType.SINGLE_LINE, description = "Path")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "From")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "To")
	@Manual("""
			NAME
				read_lines - read a range of lines of a file

			SYNOPSIS
				read_lines <path> <from> <to>

			DESCRIPTION
				Prints the lines <from> to <to>, both included, of the file
				<path>, each as "<path>:<line>: <text>", followed by a summary
				line. Lines are numbered from 1, like search_regex and every
				<position>. The text has no line terminator.

				<path> is relative to the project root and must stay inside it:
				a path that leaves the project (.., an absolute path elsewhere)
				is refused. Any file that reads as UTF-8 text will do, not only
				.java.

				All three parameters are required; one line is read_lines <path>
				<n> <n>. A <to> past the end of the file is cut to the last line:
				asking for "the next 5 lines" at the end of a file is not an
				error. A <from> past the end is, because nothing was read.

				The listing is capped like every other (see set_max_results);
				the total is always the exact number of lines in the range.

			ERRORS
				FILE_NOT_FOUND if <path> is not an existing regular file,
				PATH_OUTSIDE_PROJECT if it leaves the project,
				FILE_UNREADABLE if it is not UTF-8 text,
				VALUE_OUT_OF_RANGE if <from> is 0 or <to> is before <from>,
				LINE_OUT_OF_RANGE if <from> is past the last line.

			SEE ALSO
				search_regex(1), set_max_results(1)
			""")
	public ReadLinesCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final int from = Integer.parseInt(params[1].strip());
		final int to = Integer.parseInt(params[2].strip());
		if (from < 1)
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, "<from> is 1-based: got " + from);

		if (to < from)
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE,
					"<to> (" + to + ") is before <from> (" + from + ")");

		final Path root = context.getProjectRoot().normalize();
		final Path file;
		try {
			file = PositionParser.resolvePath(params[0].strip(), root);
		} catch (final PositionException e) {
			return CommandResult.error(e.getCode(), e.getMessage());
		}
		if (file.startsWith(root) == false)
			return CommandResult.error(ErrorCode.PATH_OUTSIDE_PROJECT,
					"'" + params[0] + "' is outside the project root " + root);

		if (Files.isRegularFile(file) == false)
			return CommandResult.error(ErrorCode.FILE_NOT_FOUND,
					"Not a file: '" + params[0] + "' (resolved against the project root " + root + ")");

		final List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.FILE_UNREADABLE, "Could not read " + params[0] + ": " + e.getMessage());
		}

		if (from > lines.size())
			return CommandResult.error(ErrorCode.LINE_OUT_OF_RANGE,
					"Line " + from + " out of range (file has " + lines.size() + " line(s)): " + params[0]);

		final String path = root.relativize(file).toString().replace('\\', '/');
		final List<SourceLine> range = new ArrayList<>();
		for (int line = from; line <= Math.min(to, lines.size()); line++)
			range.add(new SourceLine(line, lines.get(line - 1)));

		return CommandResult.ok(new CommandPayload.Lines(path, lines.size(), Listing.of(range, context.getMaxResults())));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Lines read -> {
			final StringBuilder out = new StringBuilder();
			for (final SourceLine line : read.lines().items())
				out.append(read.path()).append(':').append(line.line()).append(": ").append(line.text()).append('\n');

			out.append("read_lines: ").append(read.lines().summarize("line")).append(" of ").append(read.path())
					.append(" (").append(read.lineCount()).append(" in the file)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
