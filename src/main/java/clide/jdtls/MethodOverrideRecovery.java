package clide.jdtls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.core.Monomorphic;
import clide.core.Position;

/**
 * textDocument/implementation on a *method*, plus a second pass that recovers
 * the overrides jdtls silently omits.
 *
 * jdtls answers textDocument/implementation on a method by building a JDT
 * SearchPattern from the method element and running it over the declaring
 * type's hierarchy scope. That pattern compares parameter types by the
 * *source spelling* of the declaration, so when the target method declares
 * its own type parameter - "&lt;SHAPE extends UShape&gt; void draw(SHAPE
 * shape)" - only overrides that spell the type variable identically match.
 * Two perfectly legal override forms are therefore dropped without a word:
 *
 * - the erasure form, "void draw(UShape shape)" (a subsignature per JLS
 * 8.4.2, which javac accepts without even an -Xlint warning, and which an
 *
 * @Override annotation does not rescue), and - the renamed form, "&lt;X
 *           extends UShape&gt; void draw(X shape)".
 *
 *           On PlantUML that means 3 of the 25 real overrides of
 *           UGraphic.draw are reported - the other 22 look like they don't
 *           exist. A caller trusting the result would conclude the drawing
 *           layer has three implementations.
 *
 *           The recovery pass asks the question jdtls *does* answer
 *           correctly: textDocument/implementation on the declaring *type*
 *           (44/44 correct on PlantUML), then reads each subtype's own
 *           documentSymbol tree and keeps the members declaring a method of
 *           the same name and arity. Both result sets are unioned rather than
 *           one replacing the other - each finds cases the other misses (the
 *           pass below cannot see a subtype jdtls' type search didn't return,
 *           and jdtls sees the same-spelling overrides directly).
 *
 *           Arity, not full signature, is what is compared: reconstructing
 *           erasure from source text would mean resolving every parameter
 *           type by hand, which is exactly the work jdtls exists to do. Name
 *           plus arity within a known subtype is precise enough in practice -
 *           measured on PlantUML: 25/25 overrides found, 0 false positives -
 *           and any residual imprecision costs an extra line, never a missing
 *           one.
 *
 * Split out of JdtlsSession as a self-contained workaround for one specific
 * jdtls limitation: find(), the only entry point, returns raw Monomorphic
 * locations, not yet formatted for display - JdtlsSession.
 * findMethodImplementations() does that with formatLocation(), which needs
 * project-relative path shortening this class has no reason to know about.
 *
 * Not reusable across calls: constructed fresh with the LspClient of the
 * moment by JdtlsSession, which re-creates its own client on every start() -
 * holding on to one across a stop()/start() cycle would talk to a dead
 * process.
 */
final class MethodOverrideRecovery {

	/**
	 * Whether a declaration opens its own type parameter list - "&lt;SHAPE
	 * extends UShape&gt; void draw(...)" - as opposed to merely returning a
	 * generic type ("List&lt;String&gt; foo()"), which is why the match is
	 * anchored to the modifiers rather than looked for anywhere on the line.
	 * Only these methods need the recovery pass; a stray extra match would just
	 * cost time, never correctness.
	 */
	private static final Pattern OWN_TYPE_PARAMETERS = Pattern.compile(
			"^\\s*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|strictfp)\\s+)*<");

	private final LspClient client;

	MethodOverrideRecovery(final LspClient client) {
		this.client = client;
	}

	/**
	 * The union of jdtls' own textDocument/implementation answer and the
	 * recovery pass, deduplicated by "uri:line" - raw Location entries, not yet
	 * formatted for display.
	 */
	List<Monomorphic> find(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/implementation",
				JdtlsResponses.positionParams(position.file(), position.line(), position.column(), null), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/implementation failed: " + error);

		final List<Monomorphic> merged = new ArrayList<>(JdtlsResponses.rawLocations(response.getOrNull("result")));
		final Set<String> seen = new LinkedHashSet<>();
		for (final Monomorphic location : merged)
			seen.add(locationKey(location));

		for (final Monomorphic recovered : overridesJdtlsMisses(position))
			if (seen.add(locationKey(recovered)))
				merged.add(recovered);

		return merged;
	}

	/**
	 * The recovery pass described on the class doc: walks the declaring type's
	 * subtypes and keeps every member declaring position.name() with the same
	 * arity. Best effort throughout - anything unresolvable (no type enclosing
	 * position, no subtype, unreadable line) yields an empty list rather than an
	 * error, so the direct jdtls answer always stands on its own.
	 */
	private List<Monomorphic> overridesJdtlsMisses(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = position.file().toUri().toString();
		final String declaration = JdtlsResponses.readLineSafely(uri, position.line());
		final int arity = arityAfterName(declaration, position.name());
		if (arity < 0)
			return List.of();

		final Monomorphic declaringType = enclosingTypeNode(JdtlsResponses.documentSymbols(client, uri),
				position.line() - 1);
		if (declaringType.isMap() == false)
			return List.of();

		// Only generics make textDocument/implementation under-report, and this
		// pass costs one documentSymbol request per subtype - 363 of them on
		// TextBlock. Skip it entirely when neither the method nor its declaring
		// type is generic: there is nothing to recover, and the plain request is
		// already exhaustive.
		if (declaresTypeParameters(declaration) == false && isGenericType(declaringType) == false)
			return List.of();

		final Monomorphic start = JdtlsResponses.startOf(declaringType.getOrNull("selectionRange"));
		if (start.isMap() == false)
			return List.of();

		final Monomorphic response = client.request("textDocument/implementation",
				JdtlsResponses.positionParams(position.file(), JdtlsResponses.oneBased(JdtlsResponses.lineOf(start)),
						JdtlsResponses.oneBased(JdtlsResponses.characterOf(start)), null),
				30);
		if (JdtlsResponses.errorOf(response) != null)
			return List.of();

		final List<Monomorphic> found = new ArrayList<>();
		for (final Monomorphic subtype : JdtlsResponses.rawLocations(response.getOrNull("result")))
			collectDeclaredMethods(subtype, position.name(), arity, found);

		return found;
	}

	/**
	 * Adds to found every member of the type declared at subtype's location that
	 * declares a method named name taking arity parameters.
	 */
	private void collectDeclaredMethods(final Monomorphic subtype, final String name, final int arity,
			final List<Monomorphic> found) throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = JdtlsResponses.uriOf(subtype);
		final Monomorphic start = JdtlsResponses.startOf(JdtlsResponses.rangeOf(subtype));
		if (uri == null || start.isMap() == false)
			return;

		final Monomorphic typeNode = typeNodeAt(JdtlsResponses.documentSymbols(client, uri),
				JdtlsResponses.lineOf(start));
		if (typeNode.isMap() == false)
			return;

		for (final Monomorphic member : JdtlsResponses.childrenOf(typeNode)) {
			final Monomorphic memberStart = JdtlsResponses.startOf(member.getOrNull("selectionRange"));
			if (memberStart.isMap() == false)
				continue;

			final String declaration = JdtlsResponses.readLineSafely(uri, JdtlsResponses.lineOf(memberStart) + 1);
			if (arityAfterName(declaration, name) != arity)
				continue;

			// jdtls answers "implementation" with concrete methods only, and
			// filters abstract ones out itself; a sub-interface re-declaring the
			// method abstractly is not an implementation of it. Same rule here,
			// so the recovered results stay homogeneous with the direct ones.
			if (isAbstractDeclaration(declaration))
				continue;

			found.add(Monomorphic.mapBuilder() //
					.putString("uri", uri) //
					.put("range", member.getOrNull("selectionRange")) //
					.build());
		}
	}

	/**
	 * Whether declaration declares a method without a body - "abstract" spelled
	 * out, or an interface method, which simply ends in ";" where a concrete one
	 * opens a "{".
	 */
	static boolean isAbstractDeclaration(final String declaration) {
		final String trimmed = declaration.strip();
		return trimmed.endsWith(";") || Pattern.compile("\\babstract\\b").matcher(trimmed).find();
	}

	static boolean declaresTypeParameters(final String declaration) {
		return declaration != null && OWN_TYPE_PARAMETERS.matcher(declaration).find();
	}

	/**
	 * Whether the type is generic ("Box&lt;T&gt;"), i.e. jdtls names it with its
	 * type parameters. A raw implementation of one ("class RawBox implements
	 * Box") declares its members against the erased types and goes missing in
	 * exactly the same way a generic method's erasure override does.
	 */
	static boolean isGenericType(final Monomorphic typeNode) {
		final String rawName = typeNode.getOrNull("name").stringOrNull();
		return rawName != null && rawName.indexOf('<') >= 0;
	}

	/**
	 * Number of parameters of the method named name declared on declaration, or
	 * -1 if declaration doesn't declare one (name absent as a whole word, no
	 * parameter list, or a parameter list left unclosed on this line - a
	 * signature wrapped over several lines).
	 */
	static int arityAfterName(final String declaration, final String name) {
		if (declaration == null)
			return -1;

		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(declaration);
		if (matcher.find() == false)
			return -1;

		return arityOfParameterList(declaration, matcher.end() - 1);
	}

	/**
	 * Counts the parameters of the list opening at openIndex, ignoring commas
	 * nested inside generics, arrays or nested calls. -1 if the list never
	 * closes on this line.
	 */
	static int arityOfParameterList(final String declaration, final int openIndex) {
		int depth = 0;
		int angleDepth = 0;
		int parameters = 0;
		for (int i = openIndex; i < declaration.length(); i++) {
			final char current = declaration.charAt(i);
			if (current == '(' || current == '[') {
				depth++;
				if (depth == 1)
					parameters = 1;
			} else if (current == ')' || current == ']') {
				depth--;
				if (depth == 0)
					return declaration.substring(openIndex + 1, i).isBlank() ? 0 : parameters;
			} else if (current == '<') {
				angleDepth++;
			} else if (current == '>') {
				angleDepth--;
			} else if (current == ',' && depth == 1 && angleDepth == 0) {
				parameters++;
			}
		}
		return -1;
	}

	/** Innermost type-kind node whose whole range covers zeroBasedLine, or null. */
	static Monomorphic enclosingTypeNode(final List<Monomorphic> nodes, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false || coversLine(node, zeroBasedLine) == false)
				continue;

			final Monomorphic deeper = enclosingTypeNode(JdtlsResponses.childrenOf(node), zeroBasedLine);
			if (deeper.isMap())
				return deeper;

			if (JdtlsResponses.isTypeKind(node))
				return node;
		}
		return Monomorphic.createNull();
	}

	/** Type-kind node whose name token sits on zeroBasedLine, or null. */
	static Monomorphic typeNodeAt(final List<Monomorphic> nodes, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false)
				continue;

			if (JdtlsResponses.isTypeKind(node)
					&& JdtlsResponses.lineOf(JdtlsResponses.startOf(node.getOrNull("selectionRange"))) == zeroBasedLine)
				return node;

			final Monomorphic deeper = typeNodeAt(JdtlsResponses.childrenOf(node), zeroBasedLine);
			if (deeper.isMap())
				return deeper;
		}
		return Monomorphic.createNull();
	}

	static boolean coversLine(final Monomorphic node, final int zeroBasedLine) {
		final Monomorphic range = node.getOrNull("range");
		final Monomorphic start = range.getOrNull("start");
		final Monomorphic end = range.getOrNull("end");
		if (start.isMap() == false || end.isMap() == false)
			return false;

		return JdtlsResponses.lineOf(start) <= zeroBasedLine && zeroBasedLine <= JdtlsResponses.lineOf(end);
	}

	/** "uri:line" - identity of a location for de-duplication purposes. */
	static String locationKey(final Monomorphic location) {
		return JdtlsResponses.uriOf(location) + ":"
				+ JdtlsResponses.lineOf(JdtlsResponses.startOf(JdtlsResponses.rangeOf(location)));
	}

}
