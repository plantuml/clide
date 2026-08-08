package clide.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * The &lt;position&gt; notation clients use to point at a place in the
 * project's own source:
 * &lt;file-content-md5&gt;:&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;,
 * always 1-based, path always relative to the project root - never
 * absolute, never a file: URI. See PositionParser.parse() for the
 * notation's exact grammar and the validation a client-typed token goes
 * through before becoming one of these.
 *
 * Enforcing "relative to the project" here, in the constructor, keeps that
 * invariant true regardless of which of Position's two producers built it -
 * PositionParser.parse() (client input) or JdtlsSession.locationOf() (jdtls'
 * own results, shortened against the project root - see shortName()) - so
 * every consumer reading position.path() can rely on it without
 * re-checking.
 *
 * A location jdtls reports outside the project (a JDK/library source, a
 * file in another module) has no project-relative path to give here: clide
 * only works on the open project's own files (see CLAUDE.md), so such
 * locations are filtered out before a Position is ever built for them - see
 * JdtlsSession.isInProject().
 *
 * &lt;file-content-md5&gt; is what closes the staleness gap this class used to
 * carry as a known defect. It signs the whole content of the file the
 * position points into - the same signature Snapshot compares and
 * Md5Repository files blobs under, so one notion of "this file's content"
 * serves both. A Position built here always carries the md5 the file has
 * *now*, never the one a client sent: PositionParser.parse() compares the
 * two and refuses the token when they differ (ErrorCode.FILE_MODIFIED), so
 * a position that survived an edit fails loudly instead of pointing
 * somewhere else. md5 is null only for a position no producer could sign -
 * see toString(), which then falls back to the short form.
 *
 * Note that any edit anywhere in the file invalidates every position in it,
 * not just the ones on the edited line: the signature covers the content,
 * not the line. That is deliberate - clide already requires a rebuild after
 * an external edit (see CLAUDE.md), and a position re-derived after that
 * rebuild is the only one worth trusting.
 */
public record Position(String md5, String path, int line, int column, String name) {

	/**
	 * A &lt;file-content-md5&gt; exactly as Md5Repository.md5Of() emits it: 32
	 * hexadecimal characters, lowercase. Uppercase is refused rather than
	 * normalised - clide only ever prints one spelling, so accepting a second one
	 * would mean two tokens naming the same position and neither being the
	 * canonical one.
	 */
	public static final String MD5_REGEX = "[0-9a-f]{32}";

	private static final Pattern MD5 = Pattern.compile(MD5_REGEX);

	public Position {
		if (path != null && (path.regionMatches(true, 0, "file:", 0, 5) || Paths.get(path).isAbsolute()))
			throw new IllegalArgumentException(
					"path must be relative to the project root, not absolute and not a file: URI: " + path);

		if (md5 != null && isMd5(md5) == false)
			throw new IllegalArgumentException(
					"md5 must be 32 lowercase hexadecimal characters, as Md5Repository writes them: " + md5);
	}

	/** Whether candidate is spelled the one way a &lt;file-content-md5&gt; is written. */
	public static boolean isMd5(final String candidate) {
		return candidate != null && MD5.matcher(candidate).matches();
	}

	/**
	 * The five fields written out as the one whitespace-free token clients send
	 * and clide prints. Kept here, next to the record they spell out, so
	 * toString() and PositionParser.of() cannot drift apart on where the colons
	 * go - notably on the one case worth getting wrong once: a null md5 emits the
	 * short form, never a leading colon.
	 */
	public static String notation(final String md5, final String path, final int line, final int column,
			final String name) {
		final String position = path + ":" + line + ":" + column + ":" + name;
		return md5 == null ? position : md5 + ":" + position;
	}

	/**
	 * The file this position actually names, resolved against the root of the
	 * project it belongs to.
	 *
	 * This method exists so that path() is never handed to Paths.get() alone.
	 * path() is project-relative by construction (see the class doc above), and
	 * Paths.get(relative) resolves against the JVM's own working directory -
	 * for the daemon, whichever directory the process that happened to start it
	 * was sitting in, which has nothing to do with the opened project. That
	 * produced the worst kind of wrong answer: a path to a file that does not
	 * exist, an LSP request jdtls answers with an empty result, and a caller
	 * told "no location found" about a method that has fifty usages - no error,
	 * nothing to notice. Every consumer needing a real file goes through here.
	 */
	public Path fileIn(final Path projectRoot) {
		return projectRoot.resolve(path);
	}

	@Override
	public String toString() {
		return notation(md5, path, line, column, name);
	}

}
