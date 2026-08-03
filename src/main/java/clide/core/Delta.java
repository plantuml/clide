package clide.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything that moved between two snapshots of a project: one FileChange per
 * file that appeared, changed or vanished, and nothing at all for a file that
 * stayed as it was.
 *
 * What Snapshot.fileEventsTo() returns as a list of LSP events, this holds as
 * plain domain values - so a caller can count the creations, name the deleted
 * file or log the whole thing without parsing JSON back. The translation to
 * what jdtls expects is still here, in fileEvents(), because FileChangeType
 * already carries the protocol's numbering: one crossing point rather than two
 * representations of the same diff.
 *
 * Immutable, and built once from a complete collection: there is no add(). The
 * changes are kept sorted by path so that two Delta built from the same set of
 * changes are equal, and so that fileEvents() and toString() are reproducible -
 * a Snapshot iterates a HashMap, so the order it finds its changes in is
 * arbitrary and must not leak this far.
 *
 * A path may appear only once. Two changes for the same file would mean the
 * diff contradicts itself (created *and* deleted), and jdtls would apply
 * whichever came last in the notification - a bug that only shows up as a stale
 * diagnostic, long after.
 */
public final class Delta {

	private static final Comparator<FileChange> BY_PATH = Comparator.comparing(FileChange::path);

	private static final Delta EMPTY = new Delta(List.of());

	private final List<FileChange> changes;

	private Delta(final Collection<FileChange> changes) {
		final List<FileChange> sorted = new ArrayList<>(changes);
		sorted.sort(BY_PATH);
		this.changes = Collections.unmodifiableList(sorted);
	}

	/** The diff between two snapshots that describe the very same files. */
	public static Delta empty() {
		return EMPTY;
	}

	/** Copies the collection - later changes to the argument do not show up here. */
	public static Delta of(final Collection<FileChange> changes) {
		Objects.requireNonNull(changes, "changes");
		final Set<String> paths = new HashSet<>();
		for (final FileChange change : changes) {
			Objects.requireNonNull(change, "change");
			if (paths.add(change.path()) == false)
				throw new IllegalArgumentException("two changes for the same path: " + change.path());
		}
		return changes.isEmpty() ? EMPTY : new Delta(changes);
	}

	public static Delta of(final FileChange... changes) {
		return of(List.of(changes));
	}

	/**
	 * Nothing moved - both snapshots describe the very same files, and the caller
	 * has nothing to notify.
	 */
	public boolean isEmpty() {
		return changes.isEmpty();
	}

	/** How many files moved, all three kinds counted together. */
	public int size() {
		return changes.size();
	}

	/** The changes, sorted by path. Unmodifiable. */
	public List<FileChange> changes() {
		return changes;
	}

	/** The changes of that one kind only, sorted by path. */
	public List<FileChange> changesOfType(final FileChangeType type) {
		Objects.requireNonNull(type, "type");
		final List<FileChange> result = new ArrayList<>();
		for (final FileChange change : changes)
			if (change.type() == type)
				result.add(change);

		return Collections.unmodifiableList(result);
	}

	/**
	 * This diff as the "changes" of a workspace/didChangeWatchedFiles
	 * notification: one FileEvent per FileChange, in path order.
	 */
	public List<Monomorphic> fileEvents() {
		final List<Monomorphic> events = new ArrayList<>(changes.size());
		for (final FileChange change : changes)
			events.add(change.fileEvent());

		return Collections.unmodifiableList(events);
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o)
			return true;
		if (o instanceof Delta == false)
			return false;

		return changes.equals(((Delta) o).changes);
	}

	@Override
	public int hashCode() {
		return changes.hashCode();
	}

	@Override
	public String toString() {
		return "Delta" + changes;
	}

}
