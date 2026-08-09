package clide.edit;

/**
 * The three things a WorkspaceEdit can do to a file as a whole, named exactly
 * as LSP's own "kind" discriminant spells them ("create", "rename", "delete")
 * - see WorkspaceEdits.parse(), which matches on that string.
 *
 * clide declares all three during initialize (JdtlsSession.initializeParams())
 * even though renaming a symbol only ever needs RENAME: a capability is a
 * promise about what the client can handle, and declaring only the subset in
 * use today would have to be widened, and jdtls restarted, the first time a
 * refactoring emits one of the other two.
 */
public enum ResourceOperationKind {

	CREATE("create"), RENAME("rename"), DELETE("delete");

	private final String lspKind;

	ResourceOperationKind(final String lspKind) {
		this.lspKind = lspKind;
	}

	public String lspKind() {
		return lspKind;
	}

	/**
	 * The kind LSP named, or null when the string is none of the three. Null
	 * means "clide does not know this operation" and every caller turns it into
	 * a refusal - never into a skipped step (see EditOperation's class doc).
	 */
	public static ResourceOperationKind fromLspKind(final String candidate) {
		for (final ResourceOperationKind kind : values())
			if (kind.lspKind.equals(candidate))
				return kind;

		return null;
	}

}
