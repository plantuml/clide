package clide.util;

import java.util.ArrayList;
import java.util.List;

/**
 * One table cell: wraps its text into as many lines as needed so no line
 * exceeds maxWidth, breaking only at spaces (word wrap) - a run of text with
 * no space to break on (e.g. a single long word) is never split and stays
 * whole on its own line, even past maxWidth. A literal '\n' in the source
 * text forces a line break at that point regardless of maxWidth, on top of
 * the space-based wrapping applied to each side of it.
 */
final class Cell {

	private final List<String> lines;

	Cell(final String text, final int maxWidth) {
		lines = wrap(text, maxWidth);
	}

	List<String> lines() {
		return lines;
	}

	/** Length of this cell's longest line, once wrapped. */
	int width() {
		int width = 0;
		for (final String line : lines)
			width = Math.max(width, line.length());

		return width;
	}

	/** Number of lines this cell renders as, once wrapped. */
	int height() {
		return lines.size();
	}

	private static List<String> wrap(final String text, final int maxWidth) {
		final List<String> wrapped = new ArrayList<>();
		for (final String paragraph : text.split("\n", -1))
			wrapped.addAll(wrapParagraph(paragraph, maxWidth));

		return wrapped;
	}

	private static List<String> wrapParagraph(final String text, final int maxWidth) {
		final List<String> wrapped = new ArrayList<>();
		final StringBuilder currentLine = new StringBuilder();

		for (final String word : text.split(" ")) {
			final boolean fitsCurrentLine = currentLine.length() == 0
					|| currentLine.length() + 1 + word.length() <= maxWidth;
			if (fitsCurrentLine == false) {
				wrapped.add(currentLine.toString());
				currentLine.setLength(0);
			}
			if (currentLine.length() > 0)
				currentLine.append(' ');
			currentLine.append(word);
		}
		wrapped.add(currentLine.toString());

		return wrapped;
	}

}
