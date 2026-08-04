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

import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;
import clide.result.ErrorCode;

/**
 * A position in clide's client-facing notation:
 * "&lt;file path&gt;:&lt;line&gt;:&lt;name&gt;" - the file path relative to
 * the open project (never the daemon's own current directory), line 1-based -
 * e.g. "src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession".
 * Reifies the (file, line, name) triple every goto_*, hover and list_members
 * command used to take as three separate parameters (see CLAUDE.md): the
 * client now sends/echoes one token instead of three, and the "does this
 * name actually appear here" surface check ParamType.POSITION exists for -
 * see ClideDaemon.validate() - shares the exact same whole-word-on-line logic
 * parse() already ran, instead of jdtls-facing code re-deriving it on its
 * own.
 *
 * A position, not a symbol: it names one exact spot a symbol's name appears
 * at, not the symbol itself - a symbol usually appears at several positions
 * (its declaration, every reference to it), and nothing here aggregates
 * those together. That broader notion doesn't exist in clide yet; if it did,
 * it would hold one or more Position rather than being one itself.
 *
 * Immutable, and only ever built by parse(): any Position in hand is
 * therefore already known to name a real file, a line within range, and name
 * as a whole word on that line - callers (JdtlsSession in particular) never
 * re-validate any of that themselves.
 */
public final class Position {

	private static final Pattern NOTATION = Pattern.compile("^(.+):(\\d+):(\\w+)$");

	private final Path file;
	private final int line;
	private final String name;
	private final int column;
	private final List<Integer> columnsOnLine;

	private Position(final Path file, final int line, final String name, final List<Integer> columnsOnLine) {
		this.file = file;
		this.line = line;
		this.name = name;
		this.column = columnsOnLine.get(0);
		this.columnsOnLine = List.copyOf(columnsOnLine);
	}

	/** Absolute path of the file this position is in. */
	public Path file() {
		return file;
	}

	/** 1-based line this position is on. */
	public int line() {
		return line;
	}

	/** The name at this position, as typed. */
	public String name() {
		return name;
	}

	/** 0-based column of name() on line() - resolved once, by parse(). */
	public int column() {
		return column;
	}

	/**
	 * Every 0-based column name() occurs at as a whole word on line(), in order -
	 * column() is the first of them, and the one every jdtls request is sent
	 * against.
	 *
	 * More than one is not an error, and resolving the first is what lets a result
	 * printed by one command be pasted straight into the next. But it is the one
	 * case where clide may quietly have answered about a different symbol than the
	 * one meant - "a.foo(b.foo())" names two unrelated methods on one line - so
	 * commands report it as a WarningCode.AMBIGUOUS_NAME_ON_LINE rather than
	 * letting it pass unmentioned. See CommandResults.ambiguityWarnings().
	 */
	public List<Integer> columnsOnLine() {
		return columnsOnLine;
	}

	/** Whether name() occurs more than once as a whole word on line(). */
	public boolean isAmbiguousOnLine() {
		return columnsOnLine.size() > 1;
	}

	/**
	 * Parses "&lt;file path&gt;:&lt;line&gt;:&lt;name&gt;", the file path
	 * resolved against projectRoot - never the current working directory, so the
	 * same notation means the same thing regardless of where the clide daemon
	 * happens to have been started from. Also runs the "surface" check
	 * ParamType.POSITION exists for: the file must actually exist, line must be in
	 * range, and name must appear as a whole word on that line - all of it
	 * plain-text, no jdtls involved (a stronger, jdtls-backed check only happens
	 * once a command actually runs and asks jdtls itself).
	 *
	 * @throws PositionException with a message fit to send back to the client
	 *                           as-is, and the ErrorCode saying which of the
	 *                           failures it was: MALFORMED_POSITION,
	 *                           FILE_NOT_FOUND, FILE_UNREADABLE,
	 *                           LINE_OUT_OF_RANGE or NAME_NOT_ON_LINE. Still an
	 *                           IllegalArgumentException, so every catch site
	 *                           written before the codes existed keeps working.
	 */
	public static Position parse(final String token, final Path projectRoot) {
		final Matcher notation = NOTATION.matcher(token.trim());
		if (notation.matches() == false)
			throw new PositionException(ErrorCode.MALFORMED_POSITION,
					"Invalid position '" + token + "' - expected <file path>:<line>:<name>");

		final String pathArgument = notation.group(1);
		final Path file = resolvePath(pathArgument, projectRoot);
		if (Files.isRegularFile(file) == false)
			throw new PositionException(ErrorCode.FILE_NOT_FOUND, "Not a file: " + pathArgument);

		final int line = Integer.parseInt(notation.group(2));
		final String name = notation.group(3);

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

		final List<Integer> columns = wholeWordColumns(lines.get(line - 1), name);
		if (columns.isEmpty())
			throw new PositionException(ErrorCode.NAME_NOT_ON_LINE,
					"'" + name + "' not found on line " + line + " of " + pathArgument);

		return new Position(file, line, name, columns);
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
	 * Every 0-based column at which name occurs as a whole word on line, in
	 * order; empty when it does not occur at all.
	 *
	 * Used to stop at the first match and return just that one. It still resolves
	 * to the first - changing which occurrence wins would break the copy-a-result-
	 * into-the-next-command chaining that the whole notation exists for - but the
	 * others are now kept rather than discarded, so a command can warn that the
	 * line was ambiguous instead of silently picking one of several unrelated
	 * symbols. See columnsOnLine().
	 */
	private static List<Integer> wholeWordColumns(final String line, final String name) {
		final List<Integer> columns = new ArrayList<>();
		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line);
		while (matcher.find())
			columns.add(matcher.start());

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
		return file + ":" + line + ":" + name;
	}

}
