package clide.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One node of the open transaction chain kept by TransactionStack: everything
 * needed to undo, or hand off to its parent, the file changes made while this
 * transaction was the active (topmost) one - see TransactionStack, CLAUDE.md.
 *
 * Backed by a directory (.clide/transactions/&lt;id, its $-segments as nested
 * subdirectories&gt;) holding two things:
 * <ul>
 * <li>files/&lt;relative path&gt; - a byte-for-byte copy of &lt;relative
 * path&gt; as it stood the *first* time this transaction backed it up ("first
 * backup wins": a second modification of the same file, still within this
 * transaction, is not backed up again - the first copy already is the state
 * to restore/diff against).</li>
 * <li>created.txt - one relative path per line: files this transaction backed
 * up that did not exist on disk yet, i.e. this transaction is what created
 * them. Kept as an explicit manifest rather than the literal spec's "empty
 * file" marker, which could not be told apart from a real, genuinely-empty
 * file being backed up - a deliberate deviation, see CLAUDE.md.</li>
 * </ul>
 *
 * Package-private: commands never talk to a Transaction directly, only
 * through TransactionStack, which owns the stack discipline (see its class
 * doc) that makes "which Transaction backs up the next modification"
 * unambiguous.
 */
final class Transaction {

	private final String id;
	private final Path directory;
	private final Path projectRoot;
	private final Path filesDir;
	private final Path createdManifest;
	private final Set<String> created;

	Transaction(final String id, final Path directory, final Path projectRoot) throws IOException {
		this.id = id;
		this.directory = directory;
		this.projectRoot = projectRoot;
		this.filesDir = directory.resolve("files");
		this.createdManifest = directory.resolve("created.txt");

		Files.createDirectories(filesDir);
		this.created = new HashSet<>(readManifest());
	}

	String id() {
		return id;
	}

	Path directory() {
		return directory;
	}

	/**
	 * Records, once, the state of absoluteFile (must resolve inside
	 * projectRoot) right before a modification about to happen to it - a real
	 * backup copy if it currently exists, or a "created" manifest entry if it
	 * doesn't. No-op if this transaction already has a record for it: the first
	 * call for a given file within one transaction is the one that counts (see
	 * class doc, "first backup wins"). Called by file-modifying commands before
	 * they write anything - not by any command point 2 itself introduces.
	 */
	void backupBeforeModification(final Path absoluteFile) throws IOException {
		final String relative = normalize(projectRoot.relativize(requireWithinProject(absoluteFile)));
		if (hasBackup(relative))
			return;

		if (Files.exists(absoluteFile)) {
			final Path target = filesDir.resolve(relative);
			Files.createDirectories(target.getParent());
			Files.copy(absoluteFile, target, StandardCopyOption.COPY_ATTRIBUTES);
		} else {
			created.add(relative);
			writeManifest();
		}
	}

	/** Every relative path this transaction has a backup (or created-marker) for, sorted. */
	List<String> modifiedFiles() throws IOException {
		final Set<String> all = new HashSet<>(created);
		if (Files.isDirectory(filesDir)) {
			try (Stream<Path> walk = Files.walk(filesDir)) {
				walk.filter(Files::isRegularFile).forEach(p -> all.add(normalize(filesDir.relativize(p))));
			}
		}

		final List<String> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.naturalOrder());
		return sorted;
	}

	boolean hasBackup(final String relative) {
		return created.contains(relative) || Files.isRegularFile(filesDir.resolve(relative));
	}

	/**
	 * "Before" content for a file this transaction backed up: empty (no lines)
	 * if it was created by this transaction - there was nothing before it.
	 */
	List<String> beforeLines(final String relative) throws IOException {
		if (created.contains(relative))
			return List.of();

		return Files.readAllLines(filesDir.resolve(relative), StandardCharsets.UTF_8);
	}

	/**
	 * Restores this transaction's own backup for relative onto the live project
	 * file: deletes it if this transaction is what created it, otherwise
	 * overwrites it with the backed-up bytes. Bookkeeping is untouched - the
	 * backup itself stays right where it is, so diff_transaction/restore_file
	 * keep working the same way if called again afterwards.
	 */
	void restoreFile(final String relative) throws IOException {
		final Path live = projectRoot.resolve(relative);
		if (created.contains(relative)) {
			Files.deleteIfExists(live);
			return;
		}

		final Path backup = filesDir.resolve(relative);
		if (live.getParent() != null)
			Files.createDirectories(live.getParent());
		Files.copy(backup, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
	}

	/**
	 * Restores every file this transaction itself backed up - not, by itself,
	 * anything a still-open sub-transaction backed up; TransactionStack.rollback()
	 * gets that by calling restoreAll() on each transaction in the cascade, in
	 * the right order (see its class doc).
	 */
	void restoreAll() throws IOException {
		for (final String relative : modifiedFiles())
			restoreFile(relative);
	}

	/**
	 * Hands every backup this transaction owns to parent, "first backup wins": a
	 * relative path parent already has a record for is left untouched, since
	 * parent's copy is the older one - closer to the true state before this
	 * whole subtree started changing anything - and must be the one that
	 * survives. Used by TransactionStack.commit() to fold a (former) sub-
	 * transaction into the transaction it was opened under.
	 */
	void mergeInto(final Transaction parent) throws IOException {
		for (final String relative : modifiedFiles()) {
			if (parent.hasBackup(relative))
				continue;

			if (created.contains(relative)) {
				parent.created.add(relative);
				continue;
			}

			final Path target = parent.filesDir.resolve(relative);
			Files.createDirectories(target.getParent());
			Files.copy(filesDir.resolve(relative), target, StandardCopyOption.COPY_ATTRIBUTES);
		}
		parent.writeManifest();
	}

	/**
	 * Deletes this transaction's entire directory - backups, manifest and all -
	 * and, since sub-transaction directories nest inside their parent's (see
	 * TransactionStack), any descendant directory still physically present
	 * underneath it too.
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

	private Path requireWithinProject(final Path absoluteFile) {
		final Path normalized = absoluteFile.normalize();
		final Path root = projectRoot.normalize();
		if (normalized.startsWith(root) == false)
			throw new IllegalArgumentException("Path escapes the project root: " + absoluteFile);

		return normalized;
	}

	private static String normalize(final Path relative) {
		return relative.toString().replace('\\', '/');
	}

	private List<String> readManifest() throws IOException {
		if (Files.isRegularFile(createdManifest) == false)
			return List.of();

		return Files.readAllLines(createdManifest, StandardCharsets.UTF_8);
	}

	private void writeManifest() throws IOException {
		final List<String> sorted = new ArrayList<>(created);
		sorted.sort(Comparator.naturalOrder());
		Files.write(createdManifest, sorted, StandardCharsets.UTF_8);
	}

}
