package clide.model;

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

	@Override
	public String toString() {
		return path + ":" + line + ":" + column + ":" + name;
	}

}
