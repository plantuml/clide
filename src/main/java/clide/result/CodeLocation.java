package clide.result;

/**
 * One spot in the project, as every find_* command, list_members and
 * find_symbol name it: a project-relative path, a 1-based line, and that line's
 * own text.
 *
 * Reified rather than kept as the pre-formatted "path:line: text" string clide
 * used to pass around, because a result that is a string can be counted but not
 * inspected - a handler cannot regroup by file, and a future JSON mode would
 * have to re-parse clide's own output to say anything structured about it. The
 * display form now lives in display(), the one place that decides it.
 *
 * lineText may be empty when the line could not be read back (a location
 * outside the project, a file jdtls knows and the filesystem no longer does);
 * line is -1 when the response carried no usable range.
 */
public record CodeLocation(String path, int line, String lineText) {

	public CodeLocation {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		if (lineText == null)
			throw new IllegalArgumentException("lineText must not be null - use \"\" when the line is unavailable");
	}

	/** "path:line: line content", or "path:line" when the line's text is unavailable. */
	public String display() {
		final String located = path + ":" + line;
		return lineText.isEmpty() ? located : located + ": " + lineText;
	}

	/**
	 * The same "path:line" prefix a &lt;position&gt; parameter starts with - a
	 * caller still has to append ":<name>" to get a full position, which is why
	 * this is not called toPosition().
	 */
	public String locate() {
		return path + ":" + line;
	}

}
