package clide.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.command.answer.ErrorCode;
import clide.model.Position;

public final class PositionParser {

	/**
	 * &lt;file-content-md5&gt;:&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;,
	 * the md5 optional (see parse()).
	 *
	 * The md5 is what makes this decidable despite the path itself being allowed
	 * to contain colons (a file: URI does): the signature is of fixed width, hex,
	 * and anchored at the very start, so the only token that could be read two
	 * ways is one whose *path* begins with 32 lowercase hex characters followed by
	 * a colon. Legal on Unix, never seen; a separate separator would only have
	 * moved that same improbability elsewhere, at the cost of a second kind of
	 * boundary in a notation that otherwise has one.
	 */
	private static final Pattern NOTATION = Pattern
			.compile("^(?:(" + Position.MD5_REGEX + "):)?(.+):(\\d+):(\\d+):(\\w+)$");

	/**
	 * A prefix that is 32 hexadecimal characters and a colon, yet did not match
	 * NOTATION's md5 group - which leaves exactly one possibility, since NOTATION
	 * accepts every lowercase spelling: an md5 written with uppercase letters. See
	 * refuseMiscasedMd5().
	 */
	private static final Pattern MISCASED_MD5 = Pattern.compile("^([0-9a-fA-F]{32}):");

	private PositionParser() {
	}

	/**
	 * The token as a Position, checked against the file as it stands right now.
	 *
	 * The md5 is optional on input, and that is the one asymmetry in the notation:
	 * clide always prints the long form, a client may send either. Omitting it
	 * means "on the file currently on disk" - the token is then checked the way it
	 * always was (the file exists, the line exists, the name starts at that
	 * column) and nothing more. Sending it adds the check the rest of this exists
	 * for: content that no longer signs the same is a position produced against a
	 * file that has since changed, refused with ErrorCode.FILE_MODIFIED before
	 * line, column and name are even looked at - those would fail too, or worse
	 * not fail, and either way describe a symptom rather than the cause.
	 *
	 * Whichever form came in, the Position going out carries the file's current
	 * md5: there is no such thing, downstream, as a Position holding a signature
	 * that was true once.
	 */
	public static Position parse(final FilesRepository filesRepository, final String token) {
		final Path projectRoot = filesRepository.getProjectRoot();
		final String trimmed = token.trim();
		final Matcher notation = NOTATION.matcher(trimmed);
		if (notation.matches() == false)
			throw new PositionException(ErrorCode.MALFORMED_POSITION, "Invalid position '" + token
					+ "' - expected <file-content-md5>:<file path>:<line>:<column>:<name>, "
					+ "the <file-content-md5> being optional");

		final String md5 = notation.group(1);
		if (md5 == null)
			refuseMiscasedMd5(trimmed, token);

		final String pathArgument = notation.group(2);
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

		final String currentMd5 = md5Of(file, pathArgument);
		if (md5 != null && md5.equals(currentMd5) == false)
			throw new PositionException(ErrorCode.FILE_MODIFIED, "Stale position: " + pathArgument
					+ " has changed since this position was produced - its content no longer signs as " + md5);

		final int line = Integer.parseInt(notation.group(3));
		final int column = Integer.parseInt(notation.group(4));
		final String name = notation.group(5);

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

		return new Position(currentMd5, projectRoot.relativize(file).toString(), line, column, name);
	}

	/**
	 * The signature of what is in file right now, read through Md5Repository so a
	 * position is signed by exactly the same rule a Snapshot compares by - octets,
	 * not decoded text, which is why a file re-saved with different line endings
	 * invalidates its positions even though no column moved.
	 *
	 * The file is read a second time by the caller, for its lines. Deliberately
	 * not merged into one read: readAllLines() is what refuses a file that is not
	 * valid UTF-8 (FILE_UNREADABLE), and decoding the bytes here to save the read
	 * would silently replace the offending characters instead.
	 */
	private static String md5Of(final Path file, final String pathArgument) {
		try {
			return Md5Repository.md5Of(file);
		} catch (final IOException e) {
			throw new PositionException(ErrorCode.FILE_UNREADABLE,
					"Could not read " + pathArgument + ": " + e.getMessage());
		}
	}

	/**
	 * Refuses a token whose md5 is right in every way but its case, rather than
	 * letting it degrade into a confusing FILE_NOT_FOUND.
	 *
	 * Without this the uppercase spelling still matches NOTATION - just with no
	 * md5 group, the whole prefix swallowed into the path - and the client is told
	 * "Not a file: D41D8CD9...:src/Foo.java", which sends it looking at its path
	 * when the path was never the problem.
	 */
	private static void refuseMiscasedMd5(final String trimmed, final String token) {
		final Matcher miscased = MISCASED_MD5.matcher(trimmed);
		if (miscased.find() == false)
			return;

		throw new PositionException(ErrorCode.MALFORMED_POSITION,
				"Invalid position '" + token + "' - '" + miscased.group(1)
						+ "' is 32 hexadecimal characters, so it reads as a <file-content-md5>, "
						+ "but one is written lowercase as clide prints it");
	}

	/**
	 * The same position, built from the five fields separately rather than from
	 * the token that spells them out - what a caller has when it never read a line
	 * of text: the Lua bridge, handed a {md5, path, line, column, name} table a
	 * script got from an earlier result (see LuaArguments).
	 *
	 * Deliberately the same validation as parse(), reached the same way rather
	 * than a lighter one of its own: a Position whose content was never checked
	 * against the file as it stands now is exactly the stale position the notation
	 * exists to catch (see Position's class doc). Spelling the token out here and
	 * handing it to parse() is what keeps the two entry points from ever
	 * disagreeing; a second copy of the checks is how they would.
	 *
	 * md5 may be null, and that is what a script gets for free when it builds a
	 * position table by hand rather than passing one clide gave it: null means
	 * "against the file currently on disk", the same thing omitting the md5 means
	 * in a written token. A table that carries the md5 - every table clide hands
	 * out does - gets the staleness check with it.
	 */
	public static Position of(final FilesRepository filesRepository, final String md5, final String path,
			final int line, final int column, final String name) {
		// Checked here rather than left to parse(): a malformed md5 concatenated
		// into the token would be read as part of the path, and the script would be
		// told its file does not exist.
		if (md5 != null && Position.isMd5(md5) == false)
			throw new PositionException(ErrorCode.MALFORMED_POSITION, "Invalid <file-content-md5> '" + md5
					+ "' - expected 32 lowercase hexadecimal characters, as clide prints them");

		return parse(filesRepository, Position.notation(md5, path, line, column, name));
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

	/**
	 * pathArgument as a real Path, whether it was written project-relative or as a
	 * file: URI.
	 *
	 * Both branches raise a PositionException rather than letting the platform's
	 * own refusal escape, and the non-URI one earns that by a route the md5 made
	 * reachable. A path the running platform will not even parse - on Windows, one
	 * containing a colon - makes resolve() throw InvalidPathException, which is an
	 * IllegalArgumentException carrying no ErrorCode, so it surfaced as a bare
	 * IO_FAILED with a WindowsPathParser message. Nobody used to write such a
	 * path; a client that fumbles a &lt;file-content-md5&gt; now does, because a
	 * prefix that fails to match the md5 group is swallowed into the path -
	 * "abc123:Foo.java" out of "abc123:Foo.java:1:7:Foo". Named FILE_NOT_FOUND
	 * here so that near miss reads the same on every platform, instead of
	 * FILE_NOT_FOUND on Unix and IO_FAILED on Windows.
	 */
	public static Path resolvePath(final String pathArgument, final Path projectRoot) {
		if (isFileUri(pathArgument)) {
			try {
				return Paths.get(URI.create(pathArgument)).normalize();
			} catch (final RuntimeException e) {
				throw new PositionException(ErrorCode.MALFORMED_POSITION,
						"Invalid file URI '" + pathArgument + "': " + e.getMessage());
			}
		}

		try {
			return projectRoot.resolve(pathArgument).normalize();
		} catch (final InvalidPathException e) {
			throw new PositionException(ErrorCode.FILE_NOT_FOUND,
					"Not a usable file path on this platform: " + pathArgument + " (" + e.getReason() + ")");
		}
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
