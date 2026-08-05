package clide.result;

/**
 * One line matched by search_regex: where it is and what it says. Displayed as
 * "path:line: text", deliberately without the column a CodeLocation carries: a
 * grep hit is a line, not a symbol, so there is no symbol start to report and
 * nothing here pastes into a <position> as-is. Kept a separate type from
 * CodeLocation for the same reason - merging the two would invite treating a
 * textual match as a semantic location.
 */
public record SearchMatch(String path, int line, String text) {

	public SearchMatch {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		if (text == null)
			throw new IllegalArgumentException("text must not be null");
	}

	public String display() {
		return path + ":" + line + ": " + text;
	}

}
