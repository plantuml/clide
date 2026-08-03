package clide.core;

import java.util.Objects;

/**
 * One file that moved between two snapshots: its absolute path, and how it
 * moved - appeared, changed, or vanished.
 *
 * This is the domain form of what Snapshot.fileEventsTo() only ever produced
 * already translated into LSP. Naming the thing lets a Delta be read, counted
 * and asserted on without going through a JSON tree, and leaves the translation
 * to fileEvent() - one place, on the way out.
 *
 * A null path or a null type is refused here rather than later: both travel far
 * from where they were built, and a null type would only fail once the
 * notification is being written, with nothing left saying which file it was.
 */
public record FileChange(String path, FileChangeType type) {

	public FileChange {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(type, "type");
	}

	/**
	 * This change as the FileEvent jdtls expects inside a
	 * workspace/didChangeWatchedFiles notification.
	 */
	public Monomorphic fileEvent() {
		return type.fileEvent(path);
	}

}
