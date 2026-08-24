package clide.jdtls;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import clide.core.Monomorphic;

/**
 * Low-level reading of a jdtls/LSP JSON-RPC response, and building of the
 * request shapes JdtlsSession sends most often - shared by every command that
 * talks to jdtls (goToPosition, hover, listMembers, findSymbol) and by
 * MethodOverrideRecovery's own two-pass implementation search. Split out once
 * a second consumer (MethodOverrideRecovery) needed the same pieces: with
 * only one caller this stayed private to JdtlsSession, one file was enough.
 *
 * Deliberately just data plumbing - no method here judges whether an answer
 * is "good", only how to pull a field out of the Monomorphic shape jdtls sent
 * back, or how to build the shape it expects next. Presentation (turning a
 * location into a "path:line:column:name content" string for a client) stays in
 * JdtlsSession.formatLocation(), which needs project-relative path shortening
 * this class has no reason to know about.
 */
final class JdtlsResponses {

	/**
	 * LSP SymbolKind codes documentSymbol/workspace/symbol can return for
	 * something "class/interface/enum"-shaped - what isTypeKind() below tests
	 * for. Struct(23) included even though Java has no such kind: harmless, and
	 * one less surprise if jdtls ever reports one.
	 */
	private static final List<Integer> TYPE_SYMBOL_KINDS = List.of(5, 10, 11, 23);

	private JdtlsResponses() {
	}

	/**
	 * The "error" member of a JSON-RPC response, or null when the call
	 * succeeded - the one place a Java null still means "absent", because every
	 * caller reads it as "throw or carry on" rather than as a value.
	 */
	static Monomorphic errorOf(final Monomorphic response) {
		final Monomorphic error = response.getOrNull("error");
		return error.isNull() ? null : error;
	}

	static String uriOf(final Monomorphic location) {
		final String uri = location.getOrNull("uri").stringOrNull();
		return uri != null ? uri : location.getOrNull("targetUri").stringOrNull();
	}

	/**
	 * Also understands LocationLink (targetSelectionRange) in case a future
	 * capabilities change makes jdtls prefer that shape over plain Location
	 * (range) - harmless either way since only one shape is ever populated.
	 */
	static Monomorphic rangeOf(final Monomorphic location) {
		final Monomorphic range = location.getOrNull("range");
		return range.isMap() ? range : location.getOrNull("targetSelectionRange");
	}

	static Monomorphic startOf(final Monomorphic range) {
		return range.getOrNull("start");
	}

	static Monomorphic endOf(final Monomorphic range) {
		return range.getOrNull("end");
	}

	/** Raw, i.e. 0-based as LSP counts - see oneBased() for the client-facing form. */
	static int lineOf(final Monomorphic position) {
		return (int) position.getOrNull("line").longOrDefault(-1);
	}

	/** Raw, i.e. 0-based as LSP counts - see oneBased(). */
	static int characterOf(final Monomorphic position) {
		return (int) position.getOrNull("character").longOrDefault(-1);
	}

	/**
	 * The single conversion from LSP's 0-based line/character offsets to the
	 * 1-based line/column every client-facing notation uses (see Position,
	 * SYMBOLS.md): +1, except that -1 - "no usable value in the response" - stays
	 * -1 rather than becoming a spurious column 0.
	 *
	 * One function for both axes and one direction only, so the whole 0-vs-1
	 * question lives in this file: nothing outside clide.jdtls ever sees a 0-based
	 * number, and nothing inside it invents its own +1.
	 */
	static int oneBased(final int lspOffset) {
		return lspOffset == -1 ? -1 : lspOffset + 1;
	}

	/**
	 * Same shapes a definition/typeDefinition/implementation result can take (a
	 * single Location, a Location[], or null/absent), left raw - each caller
	 * decides what to do with them (format for display, or feed them into
	 * MethodOverrideRecovery's own dedup pass).
	 */
	static List<Monomorphic> rawLocations(final Monomorphic result) {
		if (result.isMap())
			return List.of(result);

		final List<Monomorphic> locations = new ArrayList<>();
		for (final Monomorphic item : result.elementsOf())
			if (item.isMap())
				locations.add(item);

		return locations;
	}

	/**
	 * That line, stripped of its leading/trailing whitespace - the form every
	 * caller displaying a line wants. Best-effort: null on any failure (unreadable
	 * file, malformed URI, ...).
	 */
	static String readLineSafely(final String uri, final long oneBasedLine) {
		final String raw = readRawLineSafely(uri, oneBasedLine);
		return raw == null ? null : raw.strip();
	}

	/**
	 * That line exactly as it stands on disk, indentation included - the only form
	 * a column can be counted against, since stripping the leading whitespace
	 * shifts every column on the line. Separate from readLineSafely() for that
	 * reason alone: displaying wants the stripped line, locating wants the raw
	 * one, and using one for the other is an off-by-N per tab.
	 */
	static String readRawLineSafely(final String uri, final long oneBasedLine) {
		if (uri == null || oneBasedLine < 1)
			return null;

		try {
			final Path path = Paths.get(new URI(uri));
			final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			if (oneBasedLine > lines.size())
				return null;

			return lines.get((int) oneBasedLine - 1);
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * The whole word starting exactly at oneBasedColumn of rawLine, or "" when
	 * there is none - what turns a location jdtls answered with (a uri and a
	 * range, no name anywhere) into a complete &lt;position&gt;.
	 *
	 * Reading the name back off the source rather than taking jdtls' own is
	 * deliberate: jdtls names a generic type after its source spelling with type
	 * parameters included ("AbstractUGraphic&lt;O&gt;"), and a plain Location
	 * carries no name at all. The source is the one thing PositionParser.parse() will
	 * check against anyway, so extracting it here makes what clide prints
	 * accepted by clide by construction.
	 *
	 * Word characters are \w, matching Position's own notation and whole-word
	 * check to the letter - "$" is a legal Java identifier character and is
	 * excluded by both, rather than being accepted here and refused there.
	 * Returns "" when the column does not start a word: past the end of the line,
	 * on punctuation, or in the middle of a longer word (jdtls pointing at a range
	 * that is not an identifier). Never guesses a nearby word - a name that is not
	 * exactly there is not this location's name.
	 */
	static String identifierAt(final String rawLine, final int oneBasedColumn) {
		if (rawLine == null || oneBasedColumn < 1 || oneBasedColumn > rawLine.length())
			return "";

		final int start = oneBasedColumn - 1;
		if (isWordCharacter(rawLine.charAt(start)) == false)
			return "";

		if (start > 0 && isWordCharacter(rawLine.charAt(start - 1)))
			return "";

		int end = start;
		while (end < rawLine.length() && isWordCharacter(rawLine.charAt(end)))
			end++;

		return rawLine.substring(start, end);
	}

	/** Exactly java.util.regex's \w, spelled out: [a-zA-Z0-9_]. */
	private static boolean isWordCharacter(final char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	/** The "children" of a documentSymbol node, empty when it has none. */
	static List<Monomorphic> childrenOf(final Monomorphic node) {
		return node.getOrNull("children").elementsOf();
	}

	/** Whether node is "class/interface/enum"-shaped - see TYPE_SYMBOL_KINDS. */
	static boolean isTypeKind(final Monomorphic node) {
		return TYPE_SYMBOL_KINDS.contains((int) node.getOrNull("kind").longOrDefault(-1));
	}

	/**
	 * textDocument/position request params, for a position already resolved by
	 * PositionParser.parse(). Both coordinates are 1-based here, as everywhere outside
	 * this package; the -1 back to LSP's 0-based offsets happens in the other
	 * overload and nowhere else.
	 */
	static Monomorphic positionParams(final Path file, final int oneBasedLine, final int oneBasedColumn) {
		return positionParams(file, oneBasedLine, oneBasedColumn, null);
	}

	/**
	 * Same as the other overload, with an extra "context" entry added to the
	 * request params when non-null - see JdtlsSession's context-taking
	 * goToPosition() overload.
	 */
	static Monomorphic positionParams(final Path file, final int oneBasedLine, final int oneBasedColumn,
			final Monomorphic context) {
		final Monomorphic.Builder params = Monomorphic.mapBuilder() //
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", file.toUri().toString()).build()) //
				.put("position", Monomorphic.mapBuilder() //
						.putNumber("line", oneBasedLine - 1) //
						.putNumber("character", oneBasedColumn - 1) //
						.build());
		if (context != null)
			params.put("context", context);

		return params.build();
	}

	/**
	 * textDocument/rename request params: a position, plus the name to rename to.
	 * Its own builder rather than an extra argument on positionParams(), because
	 * "newName" sits beside "position" at the top level of the request and not
	 * inside it - and because keeping the -1 to LSP's 0-based offsets in one file
	 * only works if every request shape is built in that file.
	 */
	static Monomorphic renameParams(final Path file, final int oneBasedLine, final int oneBasedColumn,
			final String newName) {
		return Monomorphic.mapBuilder() //
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", file.toUri().toString()).build()) //
				.put("position", Monomorphic.mapBuilder() //
						.putNumber("line", oneBasedLine - 1) //
						.putNumber("character", oneBasedColumn - 1) //
						.build()) //
				.putString("newName", newName) //
				.build();
	}

	/** Raw textDocument/documentSymbol tree for uri - empty on any error. */
	static List<Monomorphic> documentSymbols(final LspClient client, final String uri)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder()
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", uri).build()).build();

		final Monomorphic response = client.request("textDocument/documentSymbol", params, 30);
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException("textDocument/documentSymbol failed: " + error);

		return response.getOrNull("result").elementsOf();
	}

}
