package clide.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Absolute path -&gt; content (see SourceFile) of every source file of a project,
 * frozen at one instant - and the difference between two such instants.
 *
 * One snapshot is taken right before each workspace build (see
 * JdtlsSession.build()) and compared against a fresh one before the next build
 * (see JdtlsSession.refreshChangedFiles()). What that comparison finds is
 * exactly what jdtls has to be told through workspace/didChangeWatchedFiles,
 * since it does not watch the filesystem on its own.
 *
 * The comparison is compareWithPreviousSnapshot(), which yields a Delta - domain
 * values, translated to LSP only when fileEvents() is asked for. fileEventsTo()
 * is what callers used before that existed and now simply delegates to it: same
 * diff, read the other way round. It is kept only until its callers move over.
 *
 * What "changed" means is settled in SourceFile: the content, not the mtime. A
 * file rewritten with the very same bytes is not a change, and a file edited
 * twice within the same second is one even though its mtime never moved.
 *
 * An instance is fully built by the time it is handed out, is never mutated
 * afterwards, and never exposes its map wholesale: comparing it with another
 * snapshot, or asking it for the one md5 it recorded under a given path (see
 * md5Of()), are the only things a caller can do with it. That is also what
 * makes the comparison testable here - see SnapshotTest and SnapshotDeltaTest -
 * instead of only through a live jdtls session.
 *
 * md5Of() exists for TransactionStack (see Transaction): a caller that only
 * ever needs one file's answer - was this path modified since this snapshot
 * was taken? what did it read before? - reads that straight out of the map
 * already held in memory, rather than paying for a second full-project scan
 * (see compareWithPreviousSnapshot()) just to throw away every entry but one.
 */
public final class Snapshot {

	private final Map<String, SourceFile> files = new HashMap<>();

	private Snapshot() {

	}

	/**
	 * The snapshot of a project no build has looked at yet. Every file of any later
	 * snapshot reads as CREATED against it - which is what a session whose first
	 * snapshot never completed should report, rather than fail.
	 */
	public static Snapshot empty() {
		return new Snapshot();
	}

	public static Snapshot build(FilesRepository filesRepository) throws IOException {
		final Snapshot result = new Snapshot();
		for (final SourceFile file : filesRepository.currentSourceFiles())
			result.files.put(file.sourceFilePath(), file);

		return result;
	}

	/**
	 * What moved between previousSnapshot and this one - this one being the recent
	 * of the two, the one just taken of the tree as it stands now. CREATED for a
	 * path this one has and previousSnapshot has not, CHANGED for a path both have
	 * whose content differs, DELETED for a path previousSnapshot has and this one
	 * has not. An empty Delta means nothing moved.
	 *
	 * Note the argument order: previousSnapshot is the older one. Read it as
	 * "compare what I see now with what was there before" - the opposite of
	 * fileEventsTo(), whose receiver is the older one.
	 */
	/**
	 * The md5 this snapshot recorded for path, or null if path was not a .java
	 * source file this snapshot saw - either because it did not exist yet, or
	 * because it is outside what FilesRepository.currentSourceFiles() walks (see
	 * its own doc for what is skipped).
	 *
	 * path is matched exactly as Files.walk(projectRoot) produced it when this
	 * snapshot was built - the same projectRoot a caller already has to agree on
	 * for the lookup to land on the right entry.
	 */
	public String md5Of(final Path path) {
		final SourceFile file = files.get(path.toString());
		return file == null ? null : file.sourceFileMd5();
	}

	public Delta compareWithPreviousSnapshot(final Snapshot previousSnapshot) {
		final List<FileChange> changes = new ArrayList<>();

		for (final Map.Entry<String, SourceFile> file : files.entrySet()) {
			final SourceFile previous = previousSnapshot.files.get(file.getKey());
			if (previous == null)
				changes.add(new FileChange(file.getKey(), FileChangeType.CREATED));
			else if (previous.sourceFileMd5().equals(file.getValue().sourceFileMd5()) == false)
				changes.add(new FileChange(file.getKey(), FileChangeType.CHANGED));
		}

		for (final String path : previousSnapshot.files.keySet())
			if (files.containsKey(path) == false)
				changes.add(new FileChange(path, FileChangeType.DELETED));

		return Delta.of(changes);
	}

}
