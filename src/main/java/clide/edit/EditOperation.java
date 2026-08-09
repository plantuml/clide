package clide.edit;

/**
 * One step of a WorkspaceEdit: either a batch of text replacements inside a
 * single file (FileEdit), or something done to a file as a whole - created,
 * renamed, deleted (ResourceOperation).
 *
 * Sealed rather than open, so WorkspaceEdit.applyTo() can switch over the two
 * cases exhaustively and the compiler is the thing that notices if a third
 * kind is ever added - not a default branch quietly doing nothing to it.
 * Silently skipping an operation it did not recognise is the one failure mode
 * an edit applier must not have: the result would be a half-applied
 * refactoring that still compiles often enough to be believed.
 */
public sealed interface EditOperation permits FileEdit, ResourceOperation {

	/**
	 * The project-relative path this operation starts from - the file being
	 * edited, created, deleted, or the *old* name of a file being renamed.
	 * Always with forward slashes, whatever the platform, matching the
	 * &lt;position&gt; notation's own paths.
	 */
	String path();

}
