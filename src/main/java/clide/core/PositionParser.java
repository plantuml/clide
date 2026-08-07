package clide.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.command.answer.ErrorCode;
import clide.model.Position;

public final class PositionParser {

	private static final Pattern NOTATION = Pattern.compile("^(.+):(\\d+):(\\d+):(\\w+)$");

	private PositionParser() {
	}

	public static Position parse(final String token, final Path projectRoot) {
		final Matcher notation = NOTATION.matcher(token.trim());
		if (notation.matches() == false)
			throw new PositionException(ErrorCode.MALFORMED_POSITION,
					"Invalid position '" + token + "' - expected <file path>:<line>:<column>:<name>");

		final String pathArgument = notation.group(1);
		final Path file = resolvePath(pathArgument, projectRoot);
		// Position now requires a project-relative path (see Position's own doc) -
		// this must catch a file: URI pointing outside the project itself,
		// otherwise relativize() below would silently produce a "../"-escaping
		// string that Position's own check (isAbsolute(), starts with "file:")
		// would not have caught either.
		if (file.startsWith(projectRoot) == false)
			throw new PositionException(ErrorCode.FILE_NOT_FOUND, "Not a file in the project: " + pathArgument);
		if (Files.isRegularFile(file) == false)
			throw new PositionException(ErrorCode.FILE_NOT_FOUND, "Not a file: " + pathArgument);

		final int line = Integer.parseInt(notation.group(2));
		final int column = Integer.parseInt(notation.group(3));
		final String name = notation.group(4);

		final List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (final IOException e) {
			throw new PositionException(ErrorCode.FILE_UNREADABLE,
					"Could not read " + pathArgument + ": " + e.getMessage());
		}
		if (line < 1 || line > lines.size())
			throw new PositionException(ErrorCode.LINE_OUT_OF_RANGE,
					"Line " + line + " out of range (file has " + lines.size() + " line(s)): " + pathArgument);

		checkNameAtColumn(lines.get(line - 1), line, column, name, pathArgument);

		return new Position(projectRoot.relativize(file).toString(), line, column, name);
	}

	/**
	 * The same position, built from the four fields separately rather than from
	 * the token that spells them out - what a caller has when it never read a line
	 * of text: the Lua bridge, handed a {path, line, column, name} table a script
	 * got from an earlier result (see LuaArguments).
	 *
	 * Deliberately the same validation as parse(), reached the same way rather
	 * than a lighter one of its own: a Position whose name was never checked
	 * against the file's current content at that column is exactly the stale
	 * position the notation exists to catch (see Position's class doc, "PENDING").
	 * Spelling the token out here and handing it to parse() is what keeps the two
	 * entry points from ever disagreeing; a second copy of the checks is how they
	 * would.
	 */
	public static Position of(final String path, final int line, final int column, final String name,
			final Path projectRoot) {
		return parse(path + ":" + line + ":" + column + ":" + name, projectRoot);
	}

	private static void checkNameAtColumn(final String lineText, final int line, final int column, final String name,
			final String pathArgument) {
		final List<Integer> columns = wholeWordColumns(lineText, name);
		if (columns.contains(column))
			return;

		if (columns.isEmpty())
			throw new PositionException(ErrorCode.NAME_NOT_ON_LINE,
					"'" + name + "' not found on line " + line + " of " + pathArgument);

		final StringBuilder found = new StringBuilder();
		for (final int candidate : columns) {
			if (found.length() > 0)
				found.append(", ");

			found.append(candidate);
		}

		throw new PositionException(ErrorCode.NAME_NOT_AT_COLUMN,
				"'" + name + "' does not start at column " + column + " of line " + line + " of " + pathArgument,
				"'" + name + "' starts at column" + (columns.size() > 1 ? "s " : " ") + found + " on that line");
	}

	public static Path resolvePath(final String pathArgument, final Path projectRoot) {
		if (isFileUri(pathArgument)) {
			try {
				return Paths.get(URI.create(pathArgument)).normalize();
			} catch (final RuntimeException e) {
				throw new IllegalArgumentException("Invalid file URI '" + pathArgument + "': " + e.getMessage());
			}
		}

		return projectRoot.resolve(pathArgument).normalize();
	}

	private static boolean isFileUri(final String pathArgument) {
		return pathArgument.regionMatches(true, 0, "file:", 0, 5);
	}

	/**
	 * Every 1-based column at which name occurs as a whole word on line, in order;
	 * empty when it does not occur at all.
	 *
	 * Whole word (\bname\b) rather than a plain substring search, so "calculer"
	 * never matches inside "calculerTout" - the check would otherwise accept a
	 * column pointing at a different identifier that merely starts the same way.
	 */
	private static List<Integer> wholeWordColumns(final String line, final String name) {
		final List<Integer> columns = new ArrayList<>();
		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line);
		while (matcher.find())
			columns.add(matcher.start() + 1);

		return columns;
	}

}
