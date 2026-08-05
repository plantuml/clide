package clide.model;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The &lt;position&gt; notation clients use to point at a place in the
 * project's own source: &lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;,
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
 * PENDING (not addressed yet): a Position can go stale - the
 * file/line/name it names can stop matching the file's actual current
 * content, most easily by being reused after an edit instead of being
 * re-derived from a fresh PositionParser.parse() or jdtls result. Only
 * PositionParser.parse() (via checkNameAtColumn()) actually re-checks a
 * token against the file's live content; a Position passed around and
 * reused in-process bypasses that check entirely. Left here as a known
 * gap for whoever picks it up next.
 */
public record Position(String path, int line, int column, String name) {

	public Position {
		if (path != null && (path.regionMatches(true, 0, "file:", 0, 5) || Paths.get(path).isAbsolute()))
			throw new IllegalArgumentException(
					"path must be relative to the project root, not absolute and not a file: URI: " + path);
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
		return path + ":" + line + ":" + column + ":" + name;
	}

}
