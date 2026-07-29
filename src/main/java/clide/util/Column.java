package clide.util;

/**
 * One TextTable column: its header text, and the width above which its
 * cells wrap onto several lines - see Cell.
 */
final class Column {

	private final String header;
	private final int maxWidth;

	Column(final String header, final int maxWidth) {
		this.header = header;
		this.maxWidth = maxWidth;
	}

	String header() {
		return header;
	}

	int maxWidth() {
		return maxWidth;
	}

}
