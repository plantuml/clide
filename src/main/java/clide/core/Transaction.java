package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One node of the open transaction chain kept by TransactionStack: everything
 * needed to undo, or answer questions about, the file changes made while this
 * transaction was the active (topmost) one - see TransactionStack, CLAUDE.md.
 *
 * Backed by a Snapshot (see Snapshot.build()) taken of every .java source file
 * the moment this transaction opened - the whole tree, not just the files a
 * command is about to touch. That single choice is what replaced the previous
 * design (a lazily-grown, per-file backup copy written just before each
 * modification): comparing this opening Snapshot against a fresh one built
 * later tells, for any path, whether it was created, changed or deleted since
 * - Snapshot.compareWithPreviousSnapshot() - with no bookkeeping of which
 * files were ever touched to keep in step. The actual bytes of a changed or
 * deleted file's *old* content are not copied here either: Snapshot.build()
 * already filed them, content-addressed by md5, in the project's
 * Md5Repository store (the same one PositionParser's staleness check and
 * find_symbol positions rely on) - restoring just means reading that store
 * back (see Md5Repository.readBytes()).
 *
 * A consequence worth naming: only .java files are covered, because that is
 * all Snapshot (via FilesRepository.currentSourceFiles()) ever looks at - the
 * same scope jdtls itself is told about on every rebuild. A future
 * file-modifying command touching anything else (build files, resources)
 * would not be protected by a transaction opened this way. Deliberate: clide
 * edits Java source through jdtls, nothing else, today.
 *
 * There is nothing left for a file-modifying command to call before writing -
 * no backupBeforeModification() exists anymore. The opening Snapshot already
 * knows what every .java file looked like before *any* write happens under
 * this transaction, so a command can simply write and let a later compare
 * (restoreAll(), or a single hasBackup()/restoreFile() lookup) work out what
 * changed.
 *
 * What is written to disk under directory (.clide/transactions/&lt;id, its
 * $-segments as nested subdirectories&gt;) is only an empty marker - not a
 * manifest, not a backup: the content this transaction can restore is already
 * durably filed in Md5Repository's own store, independently of this
 * directory. The marker exists purely so TransactionStack.refuseIfDirty() can
 * still tell, after a crash, that a transaction was left open - see its doc.
 *
 * File permissions are not restored: Md5Repository's blobs never carried them
 * (they exist to answer "is this the same content", not to be a backup tool),
 * where the very first version of this class did copy them alongside the
 * bytes. Accepted: a .java source file's permissions are not something
 * clide's own edits would ever have reason to change.
 *
 * Package-private: commands never talk to a Transaction directly, only
 * through TransactionStack, which owns the stack discipline (see its class
 * doc) that makes "which Snapshot answers a question about id" unambiguous.
 */
final class Transaction {

	private final String id;
	private final Path directory;
	private final Path projectRoot;
	private final FilesRepository filesRepository;
	private final Md5Repository blobs;
	private final Snapshot opening;

	Transaction(final String id, final Path directory, final FilesRepository filesRepository) throws IOException {
		this.id = id;
		this.directory = directory;
		this.projectRoot = filesRepository.getProjectRoot();
		this.filesRepository = filesRepository;
		this.blobs = new Md5Repository(projectRoot);
		this.opening = Snapshot.build(filesRepository);

		Files.createDirectories(directory);
	}

	String id() {
		return id;
	}

	Path directory() {
		return directory;
	}

	/**
	 * Whether relative reads differently now than it did the moment this
	 * transaction opened - created, changed or deleted either way. A single
	 * lookup against the opening Snapshot plus one live md5 (Snapshot.md5Of(),
	 * Md5Repository.md5Of()) - no full-project rescan, unlike restoreAll() and
	 * modifiedFiles(), which genuinely need to know about every path at once.
	 *
	 * Always false for a path FilesRepository.isSource() would not have walked
	 * (not .java, or under a skipped directory) - see the class doc on scope.
	 * Without this check first, an untouched, out-of-scope file would still read
	 * as "no entry" in opening, exactly like a file genuinely created after this
	 * transaction opened - the two are not the same thing, and only checking
	 * scope first tells them apart.
	 */
	boolean hasBackup(final String relative) throws IOException {
		final Path absolute = projectRoot.resolve(relative);
		if (filesRepository.isSource(absolute) == false)
			return false;

		return Objects.equals(opening.md5Of(absolute), currentMd5(absolute)) == false;
	}

	/**
	 * "Before" content for relative, as this transaction's opening Snapshot
	 * recorded it: empty (no lines) if relative did not exist yet at that
	 * moment (there was nothing before it), or if relative is out of this
	 * transaction's scope in the first place (see hasBackup()).
	 */
	List<String> beforeLines(final String relative) throws IOException {
		final Path absolute = projectRoot.resolve(relative);
		if (filesRepository.isSource(absolute) == false)
			return List.of();

		final String before = opening.md5Of(absolute);
		if (before == null)
			return List.of();

		return blobs.readLines(before);
	}

	/**
	 * Restores relative to the state opening recorded for it: deleted if it did
	 * not exist yet when this transaction opened, overwritten with the exact
	 * bytes Md5Repository still holds otherwise, and left untouched if relative
	 * is out of this transaction's scope in the first place (see hasBackup()) -
	 * never deleted just for looking "created" to an opening Snapshot that was
	 * never going to record it either way. Bookkeeping is untouched - the
	 * opening Snapshot itself never changes, so diff_transaction/restore_file
	 * keep working the same way if called again afterwards.
	 */
	void restoreFile(final String relative) throws IOException {
		final Path absolute = projectRoot.resolve(relative);
		if (filesRepository.isSource(absolute) == false)
			return;

		restoreAbsolute(absolute);
	}

	/**
	 * Every relative path that reads differently now than it did when this
	 * transaction opened - one full-project Snapshot, compared once, rather than
	 * hasBackup() called path by path.
	 */
	List<String> modifiedFiles() throws IOException {
		final Snapshot live = Snapshot.build(filesRepository);
		final List<String> relatives = new ArrayList<>();
		for (final FileChange change : live.compareWithPreviousSnapshot(opening).changes())
			relatives.add(normalize(projectRoot.relativize(Paths.get(change.path()))));

		relatives.sort(Comparator.naturalOrder());
		return relatives;
	}

	/**
	 * Restores every .java file that reads differently now than it did when
	 * this transaction opened - one full-project Snapshot, one compare, then one
	 * restoreAbsolute() per path that moved: created files are deleted (opening
	 * never had them, so restoreAbsolute() takes that branch on its own),
	 * changed and deleted files get their pre-transaction bytes back.
	 *
	 * This alone is what a nested sub-transaction still open under this one no
	 * longer needs any help for: opening was taken before the sub-transaction
	 * existed, so whatever it changed already shows up in this compare, with
	 * nothing to fold in from it - see TransactionStack.rollback().
	 */
	void restoreAll() throws IOException {
		final Snapshot live = Snapshot.build(filesRepository);
		for (final FileChange change : live.compareWithPreviousSnapshot(opening).changes())
			restoreAbsolute(Paths.get(change.path()));
	}

	/**
	 * Deletes this transaction's marker directory - and, since sub-transaction
	 * directories nest inside their parent's (see TransactionStack), any
	 * descendant directory still physically present underneath it too.
	 */
	void deleteDirectory() throws IOException {
		if (Files.exists(directory) == false)
			return;

		try (Stream<Path> walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (final IOException e) {
					// best-effort - a leftover file here is caught by refuseIfDirty() on the next
					// daemon startup rather than silently ignored forever
				}
			});
		}
	}

	private void restoreAbsolute(final Path absolute) throws IOException {
		final String before = opening.md5Of(absolute);
		if (before == null) {
			Files.deleteIfExists(absolute);
			return;
		}

		final byte[] content = blobs.readBytes(before);
		if (absolute.getParent() != null)
			Files.createDirectories(absolute.getParent());
		Files.write(absolute, content);
	}

	private static String currentMd5(final Path absolute) throws IOException {
		if (Files.isRegularFile(absolute) == false)
			return null;

		return Md5Repository.md5Of(absolute);
	}

	private static String normalize(final Path relative) {
		return relative.toString().replace('\\', '/');
	}

}
