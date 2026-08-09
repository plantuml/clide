package clide.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The stack of currently-open transactions for one project - see CLAUDE.md
 * for the full protocol (open_transaction, commit_transaction,
 * rollback_transaction, diff_transaction, restore_file). One instance lives
 * for the lifetime of a ClideContext, i.e. of the clide daemon process itself
 * - see ClideContext.getTransactions(). Transactions never survive a daemon
 * restart, which is exactly why refuseIfDirty() exists.
 *
 * Deliberately a plain stack (LIFO), not a tree that lets sub-transactions
 * branch: a new id can only ever extend the id currently on top by exactly
 * one more "$segment" - e.g. with "$refactor_foo" open,
 * "open_transaction $refactor_foo$part1" is allowed, but a second, unrelated
 * "open_transaction $refactor_foo$part2" is refused until part1 is first
 * committed or rolled back. This is a deliberate simplification of the
 * literal spec (which only ever shows one nested chain at a time and never
 * two siblings open together) - it matches the class' own requested name and
 * keeps "which transaction backs up the next modification" unambiguous:
 * always whatever is on top. See CLAUDE.md.
 *
 * Each Transaction on the stack carries its own opening Snapshot of the whole
 * project (see Transaction, Snapshot) - a complete restore point, not an
 * incremental one. That is what makes closing (commit or rollback) a nested
 * chain simple: a Transaction's opening Snapshot was taken before any of its
 * still-open sub-transactions existed, so whatever they went on to change
 * already shows up when that Transaction is compared against the live tree -
 * nothing has to be folded from child to parent first. commit() therefore
 * never touches file content at all (everything is already exactly as it
 * should be on disk); it only discards restore points. rollback(id) restores
 * from id's own Snapshot alone - one compare, not one per stack level - and
 * that single restore already undoes id's own subtree in full.
 */
public final class TransactionStack {

	/**
	 * A full transaction id: one or more "$segment" chunks back to back, each
	 * segment lower-case word characters only - e.g. "$refactor_foo",
	 * "$refactor_foo$part1", "$refactor_foo$part1$a".
	 */
	public static final Pattern ID_PATTERN = Pattern.compile("(\\$[a-z0-9_]+)+");

	private static final Pattern SEGMENT = Pattern.compile("\\$([a-z0-9_]+)");
	private static final String TRANSACTIONS_DIR = ".clide/transactions";

	private final FilesRepository filesRepository;
	private final Path projectRoot;
	private final List<Transaction> stack = new ArrayList<>();

	public TransactionStack(final FilesRepository filesRepository) {
		this.filesRepository = filesRepository;
		this.projectRoot = filesRepository.getProjectRoot();
	}

	/** Whether any transaction at all is currently open - see Command.needsOpenTransaction(). */
	public boolean hasAnyOpen() {
		return stack.isEmpty() == false;
	}

	/**
	 * Every currently open transaction id, root first, most-recently-opened
	 * (topmost) last - see exit/quit's warning and terminate's refusal
	 * (DisconnectCommand, TerminateCommand) when this isn't empty.
	 */
	public List<String> openIds() {
		final List<String> ids = new ArrayList<>();
		for (final Transaction transaction : stack)
			ids.add(transaction.id());

		return ids;
	}

	/**
	 * Opens a new transaction. id must extend the id currently on top of the
	 * stack by exactly one more "$segment" - or, if the stack is empty, be a
	 * single segment (a new root transaction). Takes id's opening Snapshot of
	 * the whole project - see Transaction - which is the (only) potentially
	 * costly part of this call: the same cost as a rebuild's own file scan,
	 * paid again for every level of nesting, warm-cache after the first.
	 */
	public void open(final String id) throws IOException {
		if (ID_PATTERN.matcher(id).matches() == false)
			throw new IllegalArgumentException(
					"Invalid transaction id '" + id + "' - expected $segment, lowercase word characters only");

		final List<String> segments = segments(id);
		final String expectedParent = stack.isEmpty() ? "" : stack.get(stack.size() - 1).id();
		final String actualParent = segments.size() <= 1 ? ""
				: "$" + String.join("$", segments.subList(0, segments.size() - 1));
		if (actualParent.equals(expectedParent) == false) {
			if (stack.isEmpty())
				throw new IllegalArgumentException("Cannot open '" + id
						+ "' - no transaction is currently open, so only a single-segment (root) id is allowed");

			final String top = stack.get(stack.size() - 1).id();
			throw new IllegalArgumentException("Cannot open '" + id + "' - '" + top
					+ "' is currently the open (topmost) transaction; only a direct sub-transaction of it (" + top
					+ "$" + segments.get(segments.size() - 1) + ") can be opened next - commit or roll back '" + top
					+ "' first otherwise");
		}

		final Path directory = projectRoot.resolve(TRANSACTIONS_DIR).resolve(String.join("/", segments));
		stack.add(new Transaction(id, directory, filesRepository));
	}

	/**
	 * Commits id: implicitly commits every open sub-transaction still open under
	 * it first, then id itself. Nothing on disk changes - every file is already
	 * exactly as this transaction (and its subtree) left it - this only forgets
	 * the restore points that would otherwise let a rollback undo them.
	 */
	public void commit(final String id) throws IOException {
		final int index = indexOf(id);
		for (int i = stack.size() - 1; i >= index; i--)
			stack.get(i).deleteDirectory();

		removeFrom(index);
	}

	/**
	 * Rolls back id: restores every .java file to the state id's own opening
	 * Snapshot recorded for it - which, because that Snapshot predates every
	 * still-open sub-transaction of id, already undoes their changes too in the
	 * same single pass (see Transaction.restoreAll(), and this class' own doc).
	 * Then discards id's restore point and every still-open sub-transaction's.
	 */
	public void rollback(final String id) throws IOException {
		final int index = indexOf(id);
		stack.get(index).restoreAll();

		for (int i = stack.size() - 1; i >= index; i--)
			stack.get(i).deleteDirectory();

		removeFrom(index);
	}

	/**
	 * Every relative path that reads differently now than it did when id
	 * opened - id's own subtree in full, still-open sub-transactions included
	 * (see Transaction.modifiedFiles() and this class' own doc for why no
	 * aggregation across stack levels is needed).
	 */
	public List<String> modifiedFiles(final String id) throws IOException {
		return stack.get(indexOf(id)).modifiedFiles();
	}

	/**
	 * "Before" content of relativePath, as id's own opening Snapshot recorded
	 * it - refused if relativePath reads the same now as it did then, under
	 * id's subtree.
	 */
	public List<String> beforeLines(final String id, final String relativePath) throws IOException {
		final Transaction transaction = stack.get(indexOf(id));
		final String relative = normalizeRelative(relativePath);
		if (transaction.hasBackup(relative) == false)
			throw new IllegalArgumentException("'" + relativePath + "' was not modified under transaction " + id);

		return transaction.beforeLines(relative);
	}

	/** Current on-disk content of relativePath - no lines if the file doesn't currently exist. */
	public List<String> currentLines(final String relativePath) throws IOException {
		final Path file = resolveWithinProject(relativePath);
		if (Files.isRegularFile(file) == false)
			return List.of();

		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	/**
	 * Restores relativePath to the state it had when id opened - refused if
	 * relativePath reads the same now as it did then. Leaves every other file
	 * id's subtree modified untouched, and id itself stays open: the opening
	 * Snapshot never changes, so this (and diff_transaction) keep working the
	 * same way if called again afterwards.
	 */
	public void restoreFile(final String id, final String relativePath) throws IOException {
		final Transaction transaction = stack.get(indexOf(id));
		final String relative = normalizeRelative(relativePath);
		if (transaction.hasBackup(relative) == false)
			throw new IllegalArgumentException("'" + relativePath + "' was not modified under transaction " + id);

		transaction.restoreFile(relative);
	}

	/**
	 * Refuses to let the daemon start if .clide/transactions is not empty: a
	 * non-empty directory here means an earlier daemon crashed mid-transaction,
	 * and a fresh TransactionStack (with no on-disk record of what was open, or
	 * in what order) cannot safely resume it. Cleanup is manual, by design -
	 * silently rolling back or committing a stranger's half-finished transaction
	 * is worse than refusing to start - see CLAUDE.md.
	 *
	 * What a stranded directory holds today is only an empty marker, not a
	 * manifest: the content a leftover transaction could have restored is
	 * already durably filed, content-addressed, in Md5Repository's own store -
	 * see Transaction. There is no file list left here to inspect; the marker's
	 * one job is telling the operator which id(s) were open, from its path.
	 */
	public static void refuseIfDirty(final Path projectRoot) throws IOException {
		final Path transactions = projectRoot.resolve(TRANSACTIONS_DIR);
		if (Files.isDirectory(transactions) == false)
			return;

		try (Stream<Path> stream = Files.list(transactions)) {
			if (stream.findAny().isPresent())
				throw new IOException("Refusing to start: " + transactions
						+ " is not empty - a previous clide daemon likely crashed mid-transaction. "
						+ "Inspect and remove it manually before starting again.");
		}
	}

	Path resolveWithinProject(final String relativePath) {
		final Path root = projectRoot.normalize();
		final Path resolved = root.resolve(normalizeRelative(relativePath)).normalize();
		if (resolved.startsWith(root) == false)
			throw new IllegalArgumentException("Path escapes the project root: " + relativePath);

		return resolved;
	}

	private int indexOf(final String id) {
		for (int i = 0; i < stack.size(); i++)
			if (stack.get(i).id().equals(id))
				return i;

		throw new IllegalArgumentException("Transaction '" + id + "' is not open");
	}

	private void removeFrom(final int index) {
		for (int i = stack.size() - 1; i >= index; i--)
			stack.remove(i);
	}

	private static List<String> segments(final String id) {
		final Matcher matcher = SEGMENT.matcher(id);
		final List<String> segments = new ArrayList<>();
		while (matcher.find())
			segments.add(matcher.group(1));

		return segments;
	}

	private static String normalizeRelative(final String relativePath) {
		return relativePath.replace('\\', '/');
	}

}
