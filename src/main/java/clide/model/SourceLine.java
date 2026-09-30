package clide.model;

/**
 * One line of a file, as read_lines gives it back: its number (1-based, the
 * same numbering as search_regex and every position) and its text without the
 * line terminator.
 */
public record SourceLine(int line, String text) {

	public SourceLine {
		if (line < 1)
			throw new IllegalArgumentException("line is 1-based: " + line);

		if (text == null)
			throw new IllegalArgumentException("text must not be null - use \"\"");
	}
}
