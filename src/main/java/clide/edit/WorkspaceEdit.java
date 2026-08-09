package clide.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * An edit jdtls answered with - the return value of textDocument/rename,
 * java/getRefactorEdit and every other refactoring request - as an ordered
 * list of operations, plus the one thing clide does with it: apply it to the
 * working tree.
 *
 * This class knows nothing about LSP or JSON. WorkspaceEdits.parse() (in
 * clide.jdtls, where 0-based offsets belong) turns a Monomorphic response
 * into one of these; everything below works on plain 1-based coordinates and
 * project-relative paths, and can be tested without a jdtls anywhere near it.
 * That split is the point: the risky part of an edit applier is the splicing
 * arithmetic, and arithmetic is only worth trusting when it can be tested
 * against handwritten inputs.
 *
 * <h2>Order matters, twice, in opposite directions</h2>
 *
 * <b>Between operations</b>, the list is applied front to back, exactly as
 * jdtls listed it. It has to be: a WorkspaceEdit renaming a class edits the
 * contents of Square.java and *then* renames the file to Rectangle.java, and
 * doing those two in the other order would edit a file that no longer exists
 * under that name.
 *
 * <b>Within one file</b>, the edits are applied back to front. Every range in
 * a TextEdit is expressed against the document as it was *before* any of them
 * ran, so applying the first one shifts every later offset by the length
 * difference it introduced. Walking backwards means no edit ever moves ground
 * an edit still to come is standing on, and no offset needs adjusting. This is
 * the single most common way to get an edit applier wrong, and the reason it
 * usually goes unnoticed for a while: with edits of the same length as the
 * text they replace - a rename of a same-length identifier, say - the
 * front-to-back version produces the right answer too.
 *
 * <h2>What it preserves</h2>
 *
 * The file is read as one String and spliced by character offset, never read
 * as a list of lines and rejoined. Rejoining is what silently normalises CRLF
 * to LF and adds or drops a trailing newline on files nobody asked to touch -
 * changes that are invisible in a diff viewer, loud in a git diff, and
 * entirely clide's fault. UTF-8 in, UTF-8 out; column arithmetic is UTF-16,
 * which is what both LSP and java.lang.String count in (see TextEdit).
 *
 * <h2>What it is not</h2>
 *
 * Not atomic across files. If the fourth file of seven cannot be written, the
 * first three stay written. Making that safe is not this class's job but the
 * calling command's: no modifying command runs outside an open transaction,
 * and rolling that transaction back undoes the partial application in one
 * step (see TransactionStack, CLAUDE.md). Within a single file it *is*
 * all-or-nothing - every edit is validated, and the whole new content built
 * in memory, before anything is written.
 *
 * Not a notification to jdtls either. jdtls computed this edit against the
 * model it built at the last build, and clide never opens documents
 * (textDocument/didOpen), so nothing here tells the server the tree moved.
 * The caller does that, with JdtlsSession.refreshChangedFiles() and a
 * rebuild, or every later answer is about a project that no longer exists.
 */
public record WorkspaceEdit(List<EditOperation> operations) {

	public WorkspaceEdit {
		operations = List.copyOf(operations);
	}

	public static WorkspaceEdit empty() {
		return new WorkspaceEdit(List.of());
	}

	/**
	 * True when jdtls answered with an edit that changes nothing. Worth its own
	 * question rather than being left to look like a successful application of
	 * nothing: an empty WorkspaceEdit is what a rename of something unrenameable
	 * comes back as, and reporting it as "renamed, 0 files" would be the exact
	 * silent non-answer the project refuses everywhere else.
	 */
	public boolean isEmpty() {
		for (final EditOperation operation : operations)
			if (operation instanceof FileEdit fileEdit) {
				if (fileEdit.edits().isEmpty() == false)
					return false;

			} else {
				return false;
			}

		return true;
	}

	/**
	 * Applies every operation to the tree under projectRoot, in order, and
	 * reports what moved. Throws EditApplicationException, without having
	 * touched the file in question, on anything it will not do: overlapping
	 * edits in one file, a range past the end of a file, a path resolving
	 * outside projectRoot, a rename or create onto a name already taken.
	 */
	public AppliedEdit applyTo(final Path projectRoot) throws IOException {
		final Path root = projectRoot.toAbsolutePath().normalize();
		final Set<String> changed = new TreeSet<>();
		final List<ResourceOperation> performed = new ArrayList<>();
		int textEditCount = 0;

		for (final EditOperation operation : operations)
			switch (operation) {
			case FileEdit fileEdit -> textEditCount += applyFileEdit(root, fileEdit, changed);
			case ResourceOperation resource -> {
				applyResourceOperation(root, resource, changed);
				performed.add(resource);
			}
			}

		return new AppliedEdit(List.copyOf(changed), performed, textEditCount);
	}

	/**
	 * Returns how many edits were applied - zero, and no write at all, when the
	 * batch is empty or when splicing every edit in it happens to reproduce the
	 * file byte for byte. That second case is not a curiosity: renaming a symbol
	 * to the name it already has would otherwise rewrite the file, change its
	 * md5, and so invalidate every &lt;position&gt; anyone was holding in it -
	 * for no change at all.
	 */
	private static int applyFileEdit(final Path root, final FileEdit fileEdit, final Set<String> changed)
			throws IOException {
		if (fileEdit.edits().isEmpty())
			return 0;

		final Path file = resolve(root, fileEdit.path());
		if (Files.isRegularFile(file) == false)
			throw new EditApplicationException("edit targets a file that is not there: " + fileEdit.path());

		final String before = Files.readString(file, StandardCharsets.UTF_8);
		final String after = splice(before, fileEdit);
		if (after.equals(before))
			return 0;

		Files.writeString(file, after, StandardCharsets.UTF_8);
		changed.add(fileEdit.path());
		return fileEdit.edits().size();
	}

	/**
	 * The new content of one file, with every edit of fileEdit applied - see the
	 * class doc on why back to front. Purely in memory: nothing is written here,
	 * so a refusal below leaves the file exactly as it was.
	 */
	private static String splice(final String content, final FileEdit fileEdit) throws EditApplicationException {
		final int[] lineStarts = lineStartOffsets(content);
		final List<Splice> splices = new ArrayList<>();
		for (final TextEdit edit : fileEdit.edits()) {
			final int start = offsetOf(content, lineStarts, fileEdit.path(), edit.startLine(), edit.startColumn());
			final int end = offsetOf(content, lineStarts, fileEdit.path(), edit.endLine(), edit.endColumn());
			splices.add(new Splice(start, end, edit.newText()));
		}

		splices.sort(Comparator.comparingInt(Splice::start).thenComparingInt(Splice::end));
		checkNoOverlap(splices, fileEdit.path());

		final StringBuilder builder = new StringBuilder(content);
		for (int i = splices.size() - 1; i >= 0; i--) {
			final Splice splice = splices.get(i);
			builder.replace(splice.start(), splice.end(), splice.newText());
		}
		return builder.toString();
	}

	/**
	 * LSP states that the edits of one WorkspaceEdit must not overlap, and says
	 * nothing about what a client should do when they nevertheless do. Refusing
	 * is the only defensible answer: any of the possible orders produces a
	 * different file, all of them plausible-looking, and picking one would be
	 * clide guessing at what a refactoring meant.
	 *
	 * Touching is not overlapping - one edit ending exactly where the next
	 * begins is the normal shape of two adjacent replacements, and two
	 * zero-length insertions at the same offset are allowed too, applied in the
	 * order the sort settled on.
	 */
	private static void checkNoOverlap(final List<Splice> splices, final String path) throws EditApplicationException {
		for (int i = 1; i < splices.size(); i++) {
			final Splice previous = splices.get(i - 1);
			final Splice current = splices.get(i);
			if (current.start() < previous.end())
				throw new EditApplicationException("overlapping edits in " + path + ": [" + previous.start() + ","
						+ previous.end() + ") and [" + current.start() + "," + current.end() + ")");
		}
	}

	/**
	 * The character offset in content of 1-based (line, column).
	 *
	 * A column past the end of its line is clamped to the end of that line
	 * rather than refused: LSP says so explicitly, and jdtls uses it - the range
	 * of an edit covering a whole line is routinely given as "up to column
	 * len+1", and an editor holding a trailing selection can produce worse. A
	 * *line* past the end of the file is a different matter and is refused: it
	 * means the edit was computed against a longer file than the one on disk,
	 * which is the stale-model failure, and clamping it would write the change
	 * to the wrong place with no complaint.
	 */
	private static int offsetOf(final String content, final int[] lineStarts, final String path, final int line,
			final int column) throws EditApplicationException {
		if (line > lineStarts.length)
			throw new EditApplicationException("edit points at line " + line + " of " + path + ", which has "
					+ lineStarts.length + " line(s) - rebuild first, the edit was computed against another state");

		final int lineStart = lineStarts[line - 1];
		final int lineEnd = endOfLine(content, lineStart);
		return Math.min(lineStart + column - 1, lineEnd);
	}

	/** Offset of the first line terminator at or after from, or the end of content. */
	private static int endOfLine(final String content, final int from) {
		for (int i = from; i < content.length(); i++) {
			final char c = content.charAt(i);
			if (c == '\n' || c == '\r')
				return i;
		}
		return content.length();
	}

	/**
	 * Offset at which each line of content starts, index 0 being line 1.
	 *
	 * All three terminators are recognised - "\n", "\r\n" and a lone "\r" -
	 * because the arithmetic below has to agree with whatever jdtls counted,
	 * and jdtls reads the same bytes clide does. A file ending with a
	 * terminator has no extra empty line recorded after it: "a\n" is one line,
	 * which is what every editor, and jdtls, also say.
	 */
	private static int[] lineStartOffsets(final String content) {
		final List<Integer> starts = new ArrayList<>();
		starts.add(0);
		int i = 0;
		while (i < content.length()) {
			final char c = content.charAt(i);
			if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n')
				i++;

			if (c == '\n' || c == '\r') {
				if (i + 1 < content.length())
					starts.add(i + 1);
			}
			i++;
		}

		final int[] offsets = new int[starts.size()];
		for (int index = 0; index < offsets.length; index++)
			offsets[index] = starts.get(index);

		return offsets;
	}

	private static void applyResourceOperation(final Path root, final ResourceOperation operation,
			final Set<String> changed) throws IOException {
		final Path file = resolve(root, operation.path());
		switch (operation.kind()) {
		case RENAME -> {
			final Path target = resolve(root, operation.newPath());
			if (Files.exists(file) == false)
				throw new EditApplicationException("rename of a file that is not there: " + operation.path());

			if (Files.exists(target))
				throw new EditApplicationException(
						"rename onto a name already taken: " + operation.path() + " -> " + operation.newPath());

			createParentDirectories(target);
			Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
			changed.add(operation.path());
			changed.add(operation.newPath());
		}
		case CREATE -> {
			if (Files.exists(file))
				throw new EditApplicationException("create of a file that is already there: " + operation.path());

			createParentDirectories(file);
			Files.writeString(file, "", StandardCharsets.UTF_8);
			changed.add(operation.path());
		}
		case DELETE -> {
			if (Files.deleteIfExists(file) == false)
				throw new EditApplicationException("delete of a file that is not there: " + operation.path());

			changed.add(operation.path());
		}
		}
	}

	private static void createParentDirectories(final Path file) throws IOException {
		if (file.getParent() != null)
			Files.createDirectories(file.getParent());
	}

	/**
	 * The absolute path relative names, under root - refusing anything that
	 * resolves outside it. jdtls has no reason to answer with such a path, which
	 * is exactly why the check is worth having: the day one appears, it is
	 * either a bug or something clide should not be following, and either way
	 * writing outside the opened project is the one mistake a transaction cannot
	 * undo (it only ever snapshotted what is inside).
	 */
	private static Path resolve(final Path root, final String relative) throws EditApplicationException {
		final Path resolved = root.resolve(relative).normalize();
		if (resolved.startsWith(root) == false)
			throw new EditApplicationException("edit points outside the project: " + relative);

		return resolved;
	}

	/** One TextEdit with its coordinates already turned into character offsets. */
	private record Splice(int start, int end, String newText) {
	}

}
