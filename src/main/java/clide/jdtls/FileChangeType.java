package clide.jdtls;

/**
 * LSP watched-file change kind, as sent in the "type" field of a FileEvent
 * inside a workspace/didChangeWatchedFiles notification (see
 * JdtlsSession.refreshChangedFiles()). The numeric values are fixed by the
 * LSP protocol itself - do not renumber them.
 */
public enum FileChangeType {

	CREATED(1), CHANGED(2), DELETED(3);

	private final int lspValue;

	FileChangeType(final int lspValue) {
		this.lspValue = lspValue;
	}

	public int lspValue() {
		return lspValue;
	}

}
