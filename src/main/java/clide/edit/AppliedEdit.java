package clide.edit;

import java.util.List;

/**
 * What a WorkspaceEdit actually did, once applied - the raw material for a
 * modifying command's answer.
 *
 * changedFiles is every project-relative path whose content or existence
 * moved, sorted and deduplicated, a renamed file counting under both its old
 * and its new name (both moved, from the point of view of anyone holding a
 * path). resourceOperations lists the whole-file operations separately and in
 * the order they ran, because that is the part a caller has to report on its
 * own rather than fold into a count: "7 files changed" reads as a routine
 * refactoring, "7 files changed and Square.java is now Rectangle.java" is the
 * one thing the reader could not have guessed.
 *
 * textEditCount is the number of individual replacements, not of files - a
 * rename touching one file in forty places says 40 here and one in
 * changedFiles.
 */
public record AppliedEdit(List<String> changedFiles, List<ResourceOperation> resourceOperations, int textEditCount) {

	public AppliedEdit {
		changedFiles = List.copyOf(changedFiles);
		resourceOperations = List.copyOf(resourceOperations);
	}

	public boolean isEmpty() {
		return changedFiles.isEmpty() && resourceOperations.isEmpty();
	}

	/** The renames only, in the order they ran - see the class doc on why they are worth their own line. */
	public List<ResourceOperation> renames() {
		return resourceOperations.stream().filter(o -> o.kind() == ResourceOperationKind.RENAME).toList();
	}

	@Override
	public String toString() {
		return changedFiles.size() + " file(s), " + textEditCount + " edit(s), " + resourceOperations.size()
				+ " resource operation(s)";
	}

}
