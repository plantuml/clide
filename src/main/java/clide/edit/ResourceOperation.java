package clide.edit;

/**
 * Something done to a file as a whole rather than to its contents: created,
 * deleted, or renamed.
 *
 * newPath is set for RENAME only, and null for the other two - CREATE and
 * DELETE name a single file, which path() already carries. A rename is the
 * one clide meets in practice today: renaming a public class renames its
 * file, and jdtls can only say so if resourceOperations was declared during
 * initialize (see JdtlsSession.initializeParams()).
 *
 * LSP also allows an "options" object on each of these (overwrite,
 * ignoreIfExists, recursive, ignoreIfNotExists). None is kept here, and
 * WorkspaceEdit.applyTo() behaves as if all of them were false: a create onto
 * an existing file, or a rename onto an occupied name, is refused rather than
 * resolved. Overwriting a file that clide was not told about is not a
 * behaviour worth having by default, and a transaction only restores .java
 * files it snapshotted.
 */
public record ResourceOperation(ResourceOperationKind kind, String path, String newPath) implements EditOperation {

	public ResourceOperation {
		if (kind == null)
			throw new IllegalArgumentException("a resource operation needs a kind");

		if (path == null || path.isBlank())
			throw new IllegalArgumentException("a resource operation needs a path");

		if (kind == ResourceOperationKind.RENAME && (newPath == null || newPath.isBlank()))
			throw new IllegalArgumentException("a rename needs the name to rename to: " + path);

		if (kind != ResourceOperationKind.RENAME && newPath != null)
			throw new IllegalArgumentException("only a rename has a second path: " + kind + " " + path);
	}

	public static ResourceOperation rename(final String from, final String to) {
		return new ResourceOperation(ResourceOperationKind.RENAME, from, to);
	}

	public static ResourceOperation create(final String path) {
		return new ResourceOperation(ResourceOperationKind.CREATE, path, null);
	}

	public static ResourceOperation delete(final String path) {
		return new ResourceOperation(ResourceOperationKind.DELETE, path, null);
	}

	@Override
	public String toString() {
		if (kind == ResourceOperationKind.RENAME)
			return "rename " + path + " -> " + newPath;

		return kind.lspKind() + " " + path;
	}

}
