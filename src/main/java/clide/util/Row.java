package clide.util;

import java.util.List;

/** One table row: as many cells as the table has columns, header row included. */
final class Row {

	private final List<Cell> cells;

	Row(final List<Cell> cells) {
		this.cells = cells;
	}

	int cellWidth(final int col) {
		return cells.get(col).width();
	}

	/** Renders this row, padding every cell to widths[col], one output line per wrapped line. */
	String render(final int[] widths) {
		final StringBuilder text = new StringBuilder();
		for (int line = 0; line < height(); line++) {
			text.append('|');
			for (int col = 0; col < cells.size(); col++) {
				final List<String> lines = cells.get(col).lines();
				final String value = line < lines.size() ? lines.get(line) : "";
				text.append(' ').append(value).append(" ".repeat(widths[col] - value.length())).append(" |");
			}
			text.append('\n');
		}

		return text.toString();
	}

	private int height() {
		int height = 1;
		for (final Cell cell : cells)
			height = Math.max(height, cell.height());

		return height;
	}

}
