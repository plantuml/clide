package clide.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;

/**
 * A symbol reference in clide's client-facing notation:
 * "&lt;file path&gt;:&lt;line&gt;:&lt;name&gt;" - the file path relative to
 * the open project (never the daemon's own current directory), line 1-based -
 * e.g. "src/main/java/clide/command/ManualCommand.java:27:needsJdtlsSession".
 * Reifies the (file, line, name) triple every goto_*, hover and list_members
 * command used to take as three separate parameters (see CLAUDE.md): the
 * client now sends/echoes one token instead of three, and the "does this
 * symbol actually appear here" surface check ParamType.SYMBOL exists for -
 * see ClideDaemon.validate() - shares the exact same whole-word-on-line logic
 * parse() already ran, instead of jdtls-facing code re-deriving it on its
 * own.
 *
 * Immutable, and only ever built by parse(): any Symbol in hand is therefore
 * already known to name a real file, a line within range, and name as a
 * whole word on that line - callers (JdtlsSession in particular) never
 * re-validate any of that themselves.
 */
public final class Symbol {

	private static final Pattern NOTATION = Pattern.compile("^(.+):(\\d+):(\\w+)$");

	private final Path file;
	private final int line;
	private final String name;
	private final int column;

	private Symbol(final Path file, final int line, final String name, final int column) {
		this.file = file;
		this.line = line;
		this.name = name;
		this.column = column;
	}

	/** Absolute path of the file this symbol was found in. */
	public Path file() {
		return file;
	}

	/** 1-based line the symbol was found on. */
	public int line() {
		return line;
	}

	/** The symbol's own name, as typed. */
	public String name() {
		return name;
	}

	/** 0-based column of name() on line() - resolved once, by parse(). */
	public int column() {
		return column;
	}

	/**
	 * Parses "&lt;file path&gt;:&lt;line&gt;:&lt;name&gt;", the file path
	 * resolved against projectRoot - never the current working directory, so the
	 * same notation means the same thing regardless of where the clide daemon
	 * happens to have been started from. Also runs the "surface" check
	 * ParamType.SYMBOL exists for: the file must actually exist, line must be in
	 * range, and name must appear as a whole word on that line - all of it
	 * plain-text, no jdtls involved (a stronger, jdtls-backed check only happens
	 * once a command actually runs and asks jdtls itself).
	 *
	 * @throws IllegalArgumentException with a message fit to send back to the
	 *                                  client as-is, on any failure: malformed
	 *                                  notation, missing file, out-of-range
	 *                                  line, or name absent from that line.
	 */
	public static Symbol parse(final String token, final Path projectRoot) {
		final Matcher notation = NOTATION.matcher(token.trim());
		if (notation.matches() == false)
			throw new IllegalArgumentException(
					"Invalid symbol '" + token + "' - expected <file path>:<line>:<name>");

		final String pathArgument = notation.group(1);
		final Path file = resolvePath(pathArgument, projectRoot);
		if (Files.isRegularFile(file) == false)
			throw new IllegalArgumentException("Not a file: " + pathArgument);

		final int line = Integer.parseInt(notation.group(2));
		final String name = notation.group(3);

		final List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (final IOException e) {
			throw new IllegalArgumentException("Could not read " + pathArgument + ": " + e.getMessage());
		}
		if (line < 1 || line > lines.size())
			throw new IllegalArgumentException(
					"Line " + line + " out of range (file has " + lines.size() + " line(s)): " + pathArgument);

		final int column = wholeWordColumn(lines.get(line - 1), name);
		if (column < 0)
			throw new IllegalArgumentException(
					"Symbol '" + name + "' not found on line " + line + " of " + pathArgument);

		return new Symbol(file, line, name, column);
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

	/** 0-based column of the first whole-word match of name on line, or -1. */
	private static int wholeWordColumn(final String line, final String name) {
		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line);
		return matcher.find() ? matcher.start() : -1;
	}

	/**
	 * textDocument/hover through session, for this symbol precisely - an
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
