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
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;

/**
 * A position in clide's client-facing notation:
 * "&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;" - the file path
 * relative to the open project (never the daemon's own current directory), line
 * and column both 1-based - e.g.
 * "src/main/java/clide/command/ManualCommand.java:27:21:needsJdtlsSession".
 * Reifies the (file, line, column, name) quadruple every find_*, hover and
 * list_members command used to take as separate parameters (see CLAUDE.md): the
 * client now sends/echoes one token instead of three, and the "does this name
 * actually appear here" surface check ParamType.POSITION exists for - see
 * ClideDaemon.validate() - shares the exact same logic parse() already ran,
 * instead of jdtls-facing code re-deriving it on its own.
 *
 * The column is mandatory and carries no default. It used to be absent from the
 * notation and worked out by clide, which resolved the first whole-word
 * occurrence on the line and warned when there were several - a silent choice
 * between two unrelated symbols in "a.foo(b.foo())". Naming the column removes
 * that choice entirely: exactly one symbol answers a &lt;position&gt;, or the
 * token is refused. SYMBOLS.md' cardinal principle - any ambiguity must produce
 * an explicit error, never a silent resolution - therefore needs no warning
 * here anymore.
 *
 * The name is kept alongside the column rather than made redundant by it: it is
 * the consistency check that catches a stale token. A file edited between the
 * moment a position was printed and the moment it is sent back shifts its
 * columns, and a bare file:line:column would then point at whatever now sits
 * there, silently. parse() requires name to start exactly at column, as a whole
 * word, so such a token is refused instead of answered.
 *
 * A position, not a symbol: it names one exact spot a symbol's name appears at,
 * not the symbol itself - a symbol usually appears at several positions (its
 * declaration, every reference to it), and nothing here aggregates those
 * together. That broader notion doesn't exist in clide yet; if it did, it would
 * hold one or more Position rather than being one itself.
 *
 * Immutable, and only ever built by parse(): any Position in hand is therefore
 * already known to name a real file, a line within range, and name as a whole
 * word starting at that exact column - callers (JdtlsSession in particular)
 * never re-validate any of that themselves.
 */
public final class Position {

	private static final Pattern NOTATION = Pattern.compile("^(.+):(\\d+):(\\d+):(\\w+)$");

	private final Path file;
	private final int line;
	private final int column;
	private final String name;

	private Position(final Path file, final int line, final int column, final String name) {
		this.file = file;
		this.line = line;
		this.column = column;
		this.name = name;
	}

	/** Absolute path of the file this position is in. */
	public Path file() {
		return file;
	}

	/** 1-based line this position is on. */
	public int line() {
		return line;
	}

	/**
	 * 1-based column name() starts at on line() - as sent by the client, and
	 * verified by parse() rather than guessed.
	 *
	 * 1-based on the protocol side even though jdtls/LSP counts columns from 0:
	 * every other tool used in the same session (reading a file, grep, javac, a
	 * stack trace) counts from 1, and mixing the two conventions in one notation
	 * is how off-by-ones get shipped. The single conversion to LSP's 0-based
	 * offsets lives at the jdtls frontier, in
	 * JdtlsResponses.positionParams()/lineOf()/characterOf().
	 */
	public int column() {
		return column;
	}

	/** The name at this position, as typed. */
	public String name() {
		return name;
	}

	/**
	 * Parses "&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;", the
	 * file path resolved against projectRoot - never the current working
	 * directory, so the same notation means the same thing regardless of where the
	 * clide daemon happens to have been started from. Also runs the "surface"
	 * check ParamType.POSITION exists for: the file must actually exist, line must
	 * be in range, and name must start exactly at column as a whole word - all of
	 * it plain-text, no jdtls involved (a stronger, jdtls-backed check only
	 * happens once a command actually runs and asks jdtls itself).
	 *
	 * @throws PositionException with a message fit to send back to the client
	 *                           as-is, and the ErrorCode saying which of the
	 *                           failures it was: MALFORMED_POSITION,
	 *                           FILE_NOT_FOUND, FILE_UNREADABLE,
	 *                           LINE_OUT_OF_RANGE, NAME_NOT_ON_LINE or
	 *                           NAME_NOT_AT_COLUMN. Still an
	 *                           IllegalArgumentException, so every catch site
	 *                           written before the codes existed keeps working.
	 */
	public static Position parse(final String token, final Path projectRoot) {
		final Matcher notation = NOTATION.matcher(token.trim());
		if (notation.matches() == false)
			throw new PositionException(ErrorCode.MALFORMED_POSITION,
					"Invalid position '" + token + "' - expected <file path>:<line>:<column>:<name>");

		final String pathArgument = notation.group(1);
		final Path file = resolvePath(pathArgument, projectRoot);
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

		return new Position(file, line, column, name);
	}

	/**
	 * The consistency check the notation exists for: name must start at column
	 * (1-based) of lineText, as a whole word. Says nothing and returns when it
	 * does; throws otherwise, with two different codes on purpose:
	 *
	 * - NAME_NOT_ON_LINE when the name is nowhere on that line as a whole word.
	 * The line, or the file, is not the one the caller thinks - a stale position,
	 * or a wrong one. Correcting the column would not help.
	 *
	 * - NAME_NOT_AT_COLUMN when it is on the line, just not there. Only the column
	 * is wrong, and the hint names every column it does occur at, so the caller
	 * can fix the token without reading the file again. That list is state clide
	 * computed and the caller cannot reconstitute, which is exactly what CODING.md
	 * allows a hint to carry.
	 */
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

		throw new PositionException(ErrorCode.NAME_NOT_AT_COLUMN, "'" + name + "' does not start at column " + column
				+ " of line " + line + " of " + pathArgument,
				"'" + name + "' starts at column" + (columns.size() > 1 ? "s " : " ") + found + " on that line");
	}

	/**
	 * What a &lt;path&gt; typed by a client means, for every command that takes
	 * one - not just the &lt;file path&gt; half of this class' own notation.
	 * Accepts two forms: a plain path, resolved against projectRoot - never the
	 * daemon's own current directory (see class doc); or a "file:" URI, taken as
	 * an absolute location as-is - this is the form find_symbol, find_*, hover
	 * and list_members results are themselves printed in (see
	 * JdtlsSession.formatLocation()), so a result copied straight out of one
	 * of those and fed back as a path works without editing.
	 * projectRoot.resolve() alone can't be used for a URI: on Windows,
	 * "file:///C:/..." isn't a valid Windows path string (the colon after the
	 * drive letter - or after "file" - trips the Windows path parser), so the
	 * URI form needs java.net.URI/Paths.get(URI) instead. An already-absolute
	 * plain path is returned unchanged, since Path.resolve() of an absolute path
	 * is that path - so passing one keeps working exactly as before.
	 *
	 * Public and static because it is the single definition of that rule:
	 * search_regex resolves its &lt;initial path&gt; through this too, so
	 * "src/main/java" designates the same directory whichever command reads it.
	 * It used to call Paths.get().toAbsolutePath() instead, i.e. resolve against
	 * the daemon's working directory - which is wherever the very first "clide
	 * &lt;project&gt;" of that daemon happened to be typed from. On a daemon
	 * started from a clide checkout, "search_regex src/main/java" against a
	 * PlantUML project therefore silently searched clide's own sources and
	 * reported matches from the wrong project entirely.
	 */
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

	/**
	 * textDocument/hover through session, for this position precisely - an
	 * object-shaped wrapper around JdtlsSession.hover(this) (see CLAUDE.md,
	 * HoverCommand).
	 */
	public String retrieveJavadoc(final JdtlsSession session)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return session.hover(this);
	}

	@Override
	public String toString() {
		return file + ":" + line + ":" + column + ":" + name;
	}

}
