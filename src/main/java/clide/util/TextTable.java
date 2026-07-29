package clide.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a simple ASCII-art table: one header row followed by zero or more
 * data rows, columns aligned to the widest line in each column, borders drawn
 * with +/-/| characters. General-purpose - carries no knowledge of what the
 * columns mean, callers decide the headers and add rows.
 *
 * Cell text wider than the column's max width wraps onto several lines,
 * breaking at spaces - see Cell. A cell with no space to break on is left as
 * a single, overlong line. A literal '\n' inside a cell's text forces a line
 * break there regardless of width.
 *
 * Example, for headers "Keyword"/"Description" and one row "help"/"Shows help":
 *
 * <pre>
 * +---------+-------------+
 * | Keyword | Description |
 * +---------+-------------+
 * | help    | Shows help  |
 * +---------+-------------+
 * </pre>
 */
public class TextTable {

	private final List<Column> columns = new ArrayList<>();
	private final List<Row> rows = new ArrayList<>();

	public TextTable(final int maxColumnWidth, final String... headers) {
		for (final String header : headers)
			columns.add(new Column(header, maxColumnWidth));
	}

	/**
	 * Adds a row. values.length must equal the number of headers passed to the
	 * constructor.
	 */
	public void addRow(final String... values) {
		if (values.length != columns.size())
			throw new IllegalArgumentException("Expected " + columns.size() + " columns, got " + values.length);

		final List<Cell> cells = new ArrayList<>();
		for (int col = 0; col < values.length; col++)
			cells.add(new Cell(values[col], columns.get(col).maxWidth()));

		rows.add(new Row(cells));
	}

	/** Adds a row with every column empty - a blank separator line in the rendered table. */
	public void addEmptyRow() {
		final String[] emptyValues = new String[columns.size()];
		for (int col = 0; col < emptyValues.length; col++)
			emptyValues[col] = "";

		addRow(emptyValues);
	}

	/** Renders the whole table, header included, ending with a trailing newline. */
	public String render() {
		final Row header = headerRow();
		final int[] widths = columnWidths(header);
		final String separator = separator(widths);

		final StringBuilder text = new StringBuilder();
		text.append(separator);
		text.append(header.render(widths));
		text.append(separator);
		for (final Row row : rows)
			text.append(row.render(widths));
		text.append(separator);

		return text.toString();
	}

	private Row headerRow() {
		final List<Cell> cells = new ArrayList<>();
		for (final Column column : columns)
			cells.add(new Cell(column.header(), column.maxWidth()));

		return new Row(cells);
	}

	private int[] columnWidths(final Row header) {
		final int[] widths = new int[columns.size()];
		for (int col = 0; col < widths.length; col++)
			widths[col] = header.cellWidth(col);

		for (final Row row : rows)
			for (int col = 0; col < widths.length; col++)
				widths[col] = Math.max(widths[col], row.cellWidth(col));

		return widths;
	}

	private String separator(final int[] widths) {
		final StringBuilder line = new StringBuilder("+");
		for (final int width : widths)
			line.append("-".repeat(width + 2)).append('+');
		line.append('\n');

		return line.toString();
	}

}
