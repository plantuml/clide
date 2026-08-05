package clide.model;

/**
 * A symbol found by name (find_symbol) or listed as a member of a type
 * (list_members): where it is, plus what kind of thing it is ("class",
 * "method", "field"... - see JdtlsSession.symbolKindLabel()).
 *
 * The two commands share this shape because their results genuinely are the
 * same shape; they differ in how the set is chosen, not in what an element is.
 */
public record SymbolHit(String kind, String name, CodeLocation location) {

	public SymbolHit {
		if (kind == null || kind.isEmpty())
			throw new IllegalArgumentException("kind must not be empty");

		if (name == null)
			throw new IllegalArgumentException("name must not be null");
	}

	/**
	 * "[kind] path:line:column:name line content" - the shape clide has always printed, kept
	 * so a result still pastes straight into a &lt;position&gt; parameter.
	 * location may be null when jdtls returned a symbol without one, in which case
	 * the name stands in for it rather than the entry disappearing.
	 */
	public String display() {
		if (location == null)
			return "[" + kind + "] " + name + ": <no location>";

		return "[" + kind + "] " + location.display();
	}

}
