package clide.model;

import clide.core.FileChangeType;

/**
 * One file that moved since a named snapshot: where it is (relative to the
 * project, with '/'), how it moved, and the md5 of its content before and after.
 *
 * md5Before is "" for a file that did not exist then (CREATED), md5After is ""
 * for one that no longer exists (DELETED) - an empty string rather than null, for
 * the same reason as everywhere in a result: a key that disappears from a Lua table
 * cannot be told from one nobody wrote.
 */
public record ChangedFile(String path, FileChangeType type, String md5Before, String md5After) {

	public ChangedFile {
		if (path == null || path.isEmpty())
			throw new IllegalArgumentException("path must not be empty");

		if (type == null)
			throw new IllegalArgumentException("type must not be null");

		if (md5Before == null || md5After == null)
			throw new IllegalArgumentException("md5 must not be null - use \"\"");
	}
}
