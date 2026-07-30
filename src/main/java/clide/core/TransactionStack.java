package clide.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

	private final Path projectRoot;
	private final List<Transaction> stack = new ArrayList<>();

	public TransactionStack(final Path projectRoot) {
		this.projectRoot = projectRoot;
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
	 * Records, once, the state of absoluteFile right before a modification about
	 * to happen to it, in whichever transaction is currently on top of the stack
	 * - see Transaction.backupBeforeModification(). This is the integration
	 * point future file-modifying commands are expected to call, right before
	 * they write anything; every command that does so must also override
	 * Command.needsOpenTransaction() so ClideDaemon refuses to even reach
	 * executeCommand() with an empty stack (see ClideDaemon.runSession()) -
	 * the check here is only a defensive backstop, not the primary guard.
	 */
	public void backupBeforeModification(final Path absoluteFile) throws IOException {
		if (stack.isEmpty())
			throw new IllegalStateException("No transaction is open - call open_transaction first");

		stack.get(stack.size() - 1).backupBeforeModification(absoluteFile);
	}

	/**
	 * Opens a new transaction. id must extend the id currently on top of the
	 * stack by exactly one more "$segment" - or, if the stack is empty, be a
	 * single segment (a new root transaction). Creates id's backup directory.
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
		stack.add(new Transaction(id, directory, projectRoot));
	}

	/**
	 * Commits id: implicitly commits every open sub-transaction still open below
	 * it first (deepest first, each folding its backups into its parent - see
	 * Transaction.mergeInto()), then folds id itself into its own parent (if
	 * any), then deletes id's now-redundant directory.
	 */
	public void commit(final String id) throws IOException {
		final int index = indexOf(id);
		for (int i = stack.size() - 1; i > index; i--)
			stack.get(i).mergeInto(stack.get(i - 1));

		final Transaction target = stack.get(index);
		if (index > 0)
			target.mergeInto(stack.get(index - 1));

		target.deleteDirectory();
		removeFrom(index);
	}

	/**
	 * Rolls back id: implicitly rolls back every open sub-transaction still open
	 * below it first (deepest/most-recent first), ending with id's own restore
	 * last - id's backups are the oldest, closest to the true pre-transaction
	 * state, so restoring them last is what makes them the ones left in place.
	 * Then deletes id's directory (and, nested inside it, every sub-transaction
	 * directory not already separately removed).
	 */
	public void rollback(final String id) throws IOException {
		final int index = indexOf(id);
		for (int i = stack.size() - 1; i >= index; i--)
			stack.get(i).restoreAll();

		stack.get(index).deleteDirectory();
		removeFrom(index);
	}

	/**
	 * Every relative path modified anywhere from id down to the top of the
	 * stack: id's own changes plus every still-open sub-transaction's.
	 */
	public List<String> modifiedFiles(final String id) throws IOException {
		final int index = indexOf(id);
		final Set<String> all = new LinkedHashSet<>();
		for (int i = index; i < stack.size(); i++)
			all.addAll(stack.get(i).modifiedFiles());

		return all.stream().sorted().collect(Collectors.toList());
	}

	/**
	 * "Before" content of relativePath, as it stood right before id's subtree
	 * first touched it: id's own backup if it has one, otherwise the earliest
	 * (closest to id) still-open sub-transaction that does.
	 */
	public List<String> beforeLines(final String id, final String relativePath) throws IOException {
		final int index = indexOf(id);
		final String relative = normalizeRelative(relativePath);
		for (int i = index; i < stack.size(); i++)
			if (stack.get(i).hasBackup(relative))
				return stack.get(i).beforeLines(relative);

		throw new IllegalArgumentException("'" + relativePath + "' was not modified under transaction " + id);
	}

	/** Current on-disk content of relativePath - no lines if the file doesn't currently exist. */
	public List<String> currentLines(final String relativePath) throws IOException {
		final Path file = resolveWithinProject(relativePath);
		if (Files.isRegularFile(file) == false)
			return List.of();

		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	/**
	 * Restores relativePath to the state it had right before id's subtree first
	 * touched it - same "closest to id" lookup as beforeLines(). Leaves
	 * transaction bookkeeping untouched: the backup stays, so this (and
	 * diff_transaction) keep working the same way if called again afterwards.
	 */
	public void restoreFile(final String id, final String relativePath) throws IOException {
		final int index = indexOf(id);
		final String relative = normalizeRelative(relativePath);
		for (int i = index; i < stack.size(); i++)
			if (stack.get(i).hasBackup(relative)) {
				stack.get(i).restoreFile(relative);
				return;
			}

		throw new IllegalArgumentException("'" + relativePath + "' was not modified under transaction " + id);
	}

	/**
	 * Refuses to let the daemon start if .clide/transactions is not empty: a
	 * non-empty directory here means an earlier daemon crashed mid-transaction,
	 * and a fresh TransactionStack (with no on-disk record of what was open, or
	 * in what order) cannot safely resume it. Cleanup is manual, by design -
	 * silently rolling back or committing a stranger's half-finished transaction
	 * is worse than refusing to start - see CLAUDE.md.
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
