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
	 * The md5 stays decidable despite the path itself being allowed to contain
	 * colons (a file: URI does): it is hex, of fixed width, and anchored at the
	 * very start, so the only token that could be read two ways is one whose
	 * *path* begins with exactly Position.MD5_LENGTH lowercase hex characters
	 * followed by a colon. Legal on Unix, never seen, impossible on Windows; a
	 * separate separator would only have moved that same improbability elsewhere,
	 * at the cost of a second kind of boundary in a notation that otherwise has
	 * one.
	 *
	 * Exactly Position.MD5_LENGTH, on input as on output - not "at least": nothing
	 * past it buys the staleness check more confidence (see Position.MD5_LENGTH),
	 * so there is nothing to gain from accepting more, and one fixed shape is
	 * simpler than a range to document, test and explain.
	 */
	private static final Pattern NOTATION = Pattern
			.compile("^(?:(" + Position.MD5_REGEX + "):)?(.+):(\\d+):(\\d+):(\\w+)$");

	/**
	 * A prefix that is Position.MD5_LENGTH hexadecimal characters and a colon,
	 * yet did not match NOTATION's md5 group - which leaves exactly one
	 * possibility, since NOTATION accepts every lowercase spelling of that
	 * length: an md5 written with uppercase letters. See refuseMiscasedMd5().
	 */
	private static final Pattern MISCASED_MD5 = Pattern.compile("^([0-9a-fA-F]{" + Position.MD5_LENGTH + "}):");

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

		// No lookup anywhere: the path already said which file, so there is only
		// ever one candidate signature to compare against - the file's own, cut
		// down to the same MD5_LENGTH a token carries (see Position.abbreviate()).
		final String currentMd5 = md5Of(file, pathArgument);
		final String currentAbbreviated = Position.abbreviate(currentMd5);
		if (md5 != null && currentAbbreviated.equals(md5) == false)
			throw new PositionException(ErrorCode.FILE_MODIFIED,
					"Stale position: " + pathArgument
							+ " has changed since this position was produced - its content no longer signs as " + md5,
					staleHint(projectRoot, file, currentMd5, md5, notation));

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

		return new Position(Position.abbreviate(currentMd5), projectRoot.relativize(file).toString(), line, column,
				name);
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
	 * A best-effort hint for a FILE_MODIFIED refusal: a freshly re-derived,
	 * already-checked &lt;position&gt; for the same name, offered only when there is
	 * real evidence - not a guess - that it still names the right spot.
	 *
	 * This is not the workaround FILE_MODIFIED's ErrorCode javadoc warns against
	 * (handing back the file's current md5 so a stale token can be patched and
	 * resubmitted, which would just let a client bypass the check it just
	 * failed). What this returns, when it returns anything, is a *new* position -
	 * its own fresh md5, line and column - built the same way find_symbol or any
	 * other command would build one, then handed out only because the specific
	 * evidence below held. A client pasting it back is not evading a stale
	 * check; it is using a correct one.
	 *
	 * The evidence: staleMd5 names an old blob still sitting in Md5Repository's
	 * store (see Md5Repository.md5WithPrefix() - not guaranteed, since nothing
	 * files a blob outside a rebuild). If found, the *exact text* of the old line
	 * the token named is read back from it, and the current file is searched for
	 * that same text, byte for byte. Only when it turns up in exactly one place -
	 * not zero, not several - is there anything worth trusting: name is then
	 * looked up on that one line, and only a single unambiguous column completes
	 * the hint.
	 *
	 * Every one of those steps has a way to come up empty - the blob was never
	 * filed, the line changed too, the same line text reads twice in the file, the
	 * name isn't on it, the name reads twice on it - and every one of them means
	 * silently returning null, never a half-confident guess. In practice this
	 * hint appears far less often than it does not: any edit at all to the line
	 * itself - reindenting it, adding a trailing comment - defeats the exact-text
	 * search that is the whole safeguard against a wrong answer. What it catches
	 * is narrower and still worth having: a name that moved because code was
	 * inserted or deleted *elsewhere* in the file, leaving the line itself
	 * untouched.
	 */
	private static String staleHint(final Path projectRoot, final Path file, final String currentMd5,
			final String staleMd5, final Matcher notation) {
		try {
			final Md5Repository blobs = new Md5Repository(projectRoot);
			final String fullOldMd5 = blobs.md5WithPrefix(staleMd5);
			if (fullOldMd5 == null)
				return null;

			final List<String> oldLines = blobs.readLines(fullOldMd5);
			final int oldLine = Integer.parseInt(notation.group(3));
			if (oldLine < 1 || oldLine > oldLines.size())
				return null;
			final String oldLineText = oldLines.get(oldLine - 1);

			final List<String> currentLines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int matchAt = -1;
			for (int i = 0; i < currentLines.size(); i++) {
				if (currentLines.get(i).equals(oldLineText) == false)
					continue;
				if (matchAt != -1)
					return null; // a second identical line makes the first just as unusable

				matchAt = i;
			}
			if (matchAt == -1)
				return null;

			final String name = notation.group(5);
			final List<Integer> columns = wholeWordColumns(currentLines.get(matchAt), name);
			if (columns.size() != 1)
				return null;

			final String fresh = Position.notation(Position.abbreviate(currentMd5),
					projectRoot.relativize(file).toString(), matchAt + 1, columns.get(0), name);
			return "'" + name + "' is unchanged elsewhere in the file - now at " + fresh;
		} catch (final IOException | RuntimeException e) {
			// best-effort: any failure here means no hint, never a different error than
			// the FILE_MODIFIED already being reported
			return null;
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
						+ "' is hexadecimal and long enough to read as a <file-content-md5>, "
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
					+ "' - expected " + Position.MD5_LENGTH + " lowercase hexadecimal characters, "
					+ "as clide prints them");

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
