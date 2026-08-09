package clide.edit;

import java.util.List;

/**
 * Every text replacement one file receives from a single WorkspaceEdit.
 *
 * The order the edits are held in is the order jdtls listed them, and it is
 * deliberately *not* the order they get applied in - see
 * WorkspaceEdit.applyTo(), which sorts them and walks them backwards. Keeping
 * the received order here means what clide stored can still be compared
 * against what jdtls answered, rather than against a list clide already
 * rearranged.
 */
public record FileEdit(String path, List<TextEdit> edits) implements EditOperation {

	public FileEdit {
		if (path == null || path.isBlank())
			throw new IllegalArgumentException("a file edit needs a path");

		edits = List.copyOf(edits);
	}

	@Override
	public String toString() {
		return path + " (" + edits.size() + " edit(s))";
	}

}
