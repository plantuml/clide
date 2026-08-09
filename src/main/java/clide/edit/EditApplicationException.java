package clide.edit;

import java.io.IOException;

/**
 * A WorkspaceEdit that could not be applied as given: overlapping edits, a
 * range pointing past the end of a file, a path escaping the project root, a
 * rename onto a name already taken.
 *
 * Extends IOException on purpose rather than standing on its own. Every
 * caller of WorkspaceEdit.applyTo() is already writing files and already has
 * to handle IOException, and the distinction that matters to them - "clide
 * refused this edit" against "the disk said no" - is the *type*, catchable
 * separately by whoever wants it, not a second checked exception every
 * signature has to carry.
 *
 * What it never means: a partially applied edit that could not be finished.
 * applyTo() validates a file's whole batch of edits before writing that file
 * (see checkNoOverlap()), so a throw from it leaves the file untouched.
 * Across files, a WorkspaceEdit is not atomic and does not pretend to be -
 * that is the open transaction's job, and refusing to write outside one is
 * the modifying command's.
 */
public class EditApplicationException extends IOException {

	private static final long serialVersionUID = 1L;

	public EditApplicationException(final String message) {
		super(message);
	}

}
