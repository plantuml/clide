package clide.edit;

/**
 * One contiguous replacement inside one file: everything from
 * (startLine, startColumn) up to, but not including, (endLine, endColumn)
 * becomes newText.
 *
 * Coordinates are 1-based on both axes, like every other coordinate outside
 * clide.jdtls (see Position, SYMBOLS.md). LSP's own 0-based line/character
 * offsets are converted once, in WorkspaceEdits.parse(), and never travel
 * this far.
 *
 * An empty range (start equal to end) is a pure insertion, and an empty
 * newText a pure deletion - both are ordinary edits here, with no special
 * case anywhere.
 *
 * Note on what a column counts. LSP measures a character offset in UTF-16
 * code units, and a Java String is indexed in UTF-16 code units too, so the
 * two coincide exactly - one of the rare places where the protocol's unit
 * needs no conversion at all. An emoji in a comment (one code point, two code
 * units) therefore lands on the same offset for jdtls and for
 * WorkspaceEdit.applyTo(), rather than being off by one from there to the end
 * of the line.
 */
public record TextEdit(int startLine, int startColumn, int endLine, int endColumn, String newText) {

	public TextEdit {
		if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1)
			throw new IllegalArgumentException(
					"line and column count from 1: " + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn);

		if (endLine < startLine || (endLine == startLine && endColumn < startColumn))
			throw new IllegalArgumentException(
					"end before start: " + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn);

		if (newText == null)
			throw new IllegalArgumentException("newText must not be null - a deletion is an empty string");
	}

	@Override
	public String toString() {
		return startLine + ":" + startColumn + "-" + endLine + ":" + endColumn + " -> " + newText;
	}

}
