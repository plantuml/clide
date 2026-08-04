package clide.result;

/**
 * One line matched by search_regex: where it is and what it says. Same
 * "path:line: text" display shape as a CodeLocation, and deliberately a
 * separate type all the same - a grep hit is not a semantic location, and
 * merging the two would invite treating one as the other.
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
