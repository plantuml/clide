package clide.result;

/**
 * One spot in the project, as every find_* command, list_members and
 * find_symbol name it: a project-relative path, a 1-based line, a 1-based
 * column, and that line's own text.
 *
 * Reified rather than kept as the pre-formatted "path:line: text" string clide
 * used to pass around, because a result that is a string can be counted but not
 * inspected - a handler cannot regroup by file, and a future JSON mode would
 * have to re-parse clide's own output to say anything structured about it. The
 * display form now lives in display(), the one place that decides it.
 *
 * The column exists so that what clide prints and what clide accepts are the
 * same notation (see Position, SYMBOLS.md): a location printed as
 * "path:line:column" becomes a valid &lt;position&gt; by appending ":<name>",
 * nothing else to work out. It is the start of the symbol's own range as jdtls
 * reported it, converted from LSP's 0-based character offset at the one place
 * that conversion happens (JdtlsSession.locationOf()).
 *
 * lineText may be empty when the line could not be read back (a location
 * outside the project, a file jdtls knows and the filesystem no longer does);
 * line and column are -1 when the response carried no usable range.
 */
public record CodeLocation(String path, int line, int column, String lineText) {

	public CodeLocation {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		if (lineText == null)
			throw new IllegalArgumentException("lineText must not be null - use \"\" when the line is unavailable");
	}

	/**
	 * "path:line:column: line content", or "path:line:column" when the line's text
	 * is unavailable.
	 */
	public String display() {
		final String located = locate();
		return lineText.isEmpty() ? located : located + ": " + lineText;
	}

	/**
	 * The same "path:line:column" prefix a &lt;position&gt; parameter starts with
	 * - a caller still has to append ":<name>" to get a full position, which is
	 * why this is not called toPosition().
	 */
	public String locate() {
		return path + ":" + line + ":" + column;
	}

}
