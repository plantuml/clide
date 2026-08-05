package clide.model;

/**
 * One spot in the project, as every find_* command, list_members and
 * find_symbol name it: a project-relative path, a 1-based line, a 1-based
 * column, the name of the symbol standing there, and that line's own text.
 *
 * Reified rather than kept as the pre-formatted "path:line: text" string clide
 * used to pass around, because a result that is a string can be counted but not
 * inspected - a handler cannot regroup by file, and a future JSON mode would
 * have to re-parse clide's own output to say anything structured about it. The
 * display form now lives in display(), the one place that decides it.
 *
 * The first four fields are exactly a &lt;position&gt; (see Position,
 * SYMBOLS.md), which is the point: what clide prints and what clide accepts are
 * the same notation, so a result pastes into the next command untouched, with
 * nothing to append and nothing to count by hand. Line and column come from the
 * start of the range jdtls reported, converted from LSP's 0-based offsets at the
 * one place that conversion happens; name is read back out of the source line at
 * that column, so it is the source's own spelling rather than jdtls' (which
 * writes a generic type "AbstractUGraphic&lt;O&gt;", type parameters included,
 * where the position notation only ever takes the bare word).
 *
 * lineText may be empty when the line could not be read back (a location outside
 * the project, a file jdtls knows and the filesystem no longer does); name is
 * empty in that same case, and when the column turns out not to start a word at
 * all. line and column are -1 when the response carried no usable range. An
 * entry is never dropped for any of that - it is reported for what it is, one
 * notch short of a full position.
 */
public record CodeLocation(String path, int line, int column, String name, String lineText) {

	public CodeLocation {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		if (name == null)
			throw new IllegalArgumentException("name must not be null - use \"\" when the symbol is unnamed");

		if (lineText == null)
			throw new IllegalArgumentException("lineText must not be null - use \"\" when the line is unavailable");
	}

	/**
	 * "path:line:column:name line content" - a whole &lt;position&gt; as one
	 * whitespace-free token, then a space, then the line as it reads. The
	 * separator is a space and not a colon on purpose: a colon would sit right
	 * where the notation itself uses colons, and a client splitting on the first
	 * whitespace gets the position back with no parsing at all.
	 *
	 * Falls back to whatever is known when something is missing: no line text
	 * leaves the position alone, and no name leaves "path:line:column" - which is
	 * not a valid &lt;position&gt;, and reads as the incomplete answer it is
	 * rather than as a fabricated one.
	 */
	public String display() {
		final String located = position();
		return lineText.isEmpty() ? located : located + " " + lineText;
	}

	/**
	 * The &lt;position&gt; token for this location - "path:line:column:name",
	 * ready to be sent back to any command taking one, or "path:line:column" when
	 * the name could not be read back (see the class doc).
	 */
	public String position() {
		return name.isEmpty() ? path + ":" + line + ":" + column : path + ":" + line + ":" + column + ":" + name;
	}

}
