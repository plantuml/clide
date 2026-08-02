package clide.jdtls;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import clide.json.Monomorphic;

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
 * location into a "path:line: content" string for a client) stays in
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

	static int lineOf(final Monomorphic position) {
		return (int) position.getOrNull("line").longOrDefault(-1);
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

	/** Best-effort: null on any failure (unreadable file, malformed URI, ...). */
	static String readLineSafely(final String uri, final long oneBasedLine) {
		if (uri == null || oneBasedLine < 1)
			return null;

		try {
			final Path path = Paths.get(new URI(uri));
			final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			if (oneBasedLine > lines.size())
				return null;

			return lines.get((int) oneBasedLine - 1).strip();
		} catch (final Exception e) {
			return null;
		}
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
	 * Position.parse().
	 */
	static Monomorphic positionParams(final Path file, final int oneBasedLine, final int column) {
		return positionParams(file, oneBasedLine, column, null);
	}

	/**
	 * Same as the other overload, with an extra "context" entry added to the
	 * request params when non-null - see JdtlsSession's context-taking
	 * goToPosition() overload.
	 */
	static Monomorphic positionParams(final Path file, final int oneBasedLine, final int column,
			final Monomorphic context) {
		final Monomorphic.Builder params = Monomorphic.mapBuilder() //
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", file.toUri().toString()).build()) //
				.put("position", Monomorphic.mapBuilder() //
						.putNumber("line", oneBasedLine - 1) //
						.putNumber("character", column) //
						.build());
		if (context != null)
			params.put("context", context);

		return params.build();
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
