package clide.model;

import java.util.regex.Pattern;

public record CodeLocation(Position position, String lineText) {

	public CodeLocation {
		if (position == null)
			throw new IllegalArgumentException("position must not be null");

		if (lineText == null)
			throw new IllegalArgumentException("lineText must not be null - use \"\" when the line is unavailable");

		// Consistency between position and lineText: -1 means "no usable
		// line/column" (see JdtlsResponses.oneBased()), which cannot come with an
		// actual line of text to show - and a name, when there is one, always came
		// from that same line (see JdtlsSession.locationOf(): both are read off the
		// same rawLine), so it must still be findable there, as a whole word, once
		// only leading/trailing whitespace has been stripped away.
		if ((position.line() == -1 || position.column() == -1) && lineText.isEmpty() == false)
			throw new IllegalArgumentException("position " + position
					+ " carries no usable line/column, but lineText is not empty: \"" + lineText + "\"");

		final String name = position.name();
		if (name != null && name.isEmpty() == false && appearsAsWholeWord(lineText, name) == false)
			throw new IllegalArgumentException(
					"position's name \"" + name + "\" does not appear as a whole word on lineText \"" + lineText + "\"");
	}

	/**
	 * Whether name occurs as a whole word (\bname\b) somewhere on lineText - not
	 * necessarily at any particular column, since lineText is the stripped line
	 * (see JdtlsSession.locationOf()) while name was read off the raw,
	 * unstripped one: stripping only removes leading/trailing whitespace, so a
	 * word present on the raw line survives it, just not always at the same
	 * offset.
	 */
	private static boolean appearsAsWholeWord(final String lineText, final String name) {
		return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(lineText).find();
	}

	public String display() {
		return lineText.isEmpty() ? position.toString() : position.toString() + " " + lineText;
	}

}
