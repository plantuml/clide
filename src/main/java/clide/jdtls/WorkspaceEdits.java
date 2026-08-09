package clide.jdtls;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import clide.core.Monomorphic;
import clide.edit.EditApplicationException;
import clide.edit.EditOperation;
import clide.edit.FileEdit;
import clide.edit.ResourceOperation;
import clide.edit.ResourceOperationKind;
import clide.edit.TextEdit;
import clide.edit.WorkspaceEdit;

/**
 * Reads the WorkspaceEdit jdtls answers a refactoring request with - the
 * result of textDocument/rename, and later java/getRefactorEdit - into
 * clide.edit's own model.
 *
 * This is the boundary, and it is the only file in clide that knows a
 * WorkspaceEdit is made of JSON: everything downstream works on 1-based
 * coordinates and project-relative paths. It is also where LSP's 0-based
 * line/character offsets are converted, through JdtlsResponses.oneBased() and
 * nothing else - see that method on why the whole 0-vs-1 question lives in
 * this package.
 *
 * <h2>Two shapes, and why only one of them can say everything</h2>
 *
 * LSP lets a server answer in either of two forms. The legacy one, "changes",
 * is a bare map from document URI to TextEdit[]: unordered, and structurally
 * incapable of expressing anything but a change of contents. The current one,
 * "documentChanges", is an ordered list that may also carry resource
 * operations - a file created, renamed, deleted. clide declares
 * documentChanges during initialize (JdtlsSession.initializeParams()) and so
 * expects that second form in practice; the first is still read, because a
 * server is free to fall back to it and answering "I cannot read your answer"
 * to a well-formed one would be clide's bug, not jdtls'.
 *
 * <h2>Refusing beats skipping</h2>
 *
 * Every unknown or malformed piece raises. An entry with no recognisable
 * shape, a resource operation whose kind is not one of the three, a snippet
 * edit (jdtls' own non-standard extension, whose newText carries $1/${0}
 * placeholders that are not literal text): all refused, none ignored. The
 * reason is the one in EditOperation's class doc - an edit applier that
 * quietly drops the step it did not understand produces a half-applied
 * refactoring, and a half-applied refactoring compiles often enough to be
 * believed.
 */
final class WorkspaceEdits {

	/** LSP InsertTextFormat.Snippet - see the class doc on why one is refused rather than applied. */
	private static final long SNIPPET_FORMAT = 2;

	private WorkspaceEdits() {
	}

	/**
	 * The edit result carries, as operations against files under projectRoot. An
	 * absent, null or empty result yields WorkspaceEdit.empty() - a server
	 * declining to edit anything is a fact to report, not a failure to parse
	 * (the caller decides what an empty edit means for the command it is
	 * serving, see WorkspaceEdit.isEmpty()).
	 */
	static WorkspaceEdit parse(final Monomorphic result, final Path projectRoot) throws EditApplicationException {
		if (result == null || result.isMap() == false)
			return WorkspaceEdit.empty();

		final Path root = projectRoot.toAbsolutePath().normalize();
		final Monomorphic documentChanges = result.getOrNull("documentChanges");
		if (documentChanges.isList())
			return new WorkspaceEdit(parseDocumentChanges(documentChanges, root));

		final Monomorphic changes = result.getOrNull("changes");
		if (changes.isMap())
			return new WorkspaceEdit(parseChanges(changes, root));

		return WorkspaceEdit.empty();
	}

	/**
	 * The ordered form. Order is preserved exactly as received and never sorted:
	 * a rename of a public class edits the contents of the old file and then
	 * renames it, and those two steps only make sense in that order (see
	 * WorkspaceEdit's class doc).
	 */
	private static List<EditOperation> parseDocumentChanges(final Monomorphic documentChanges, final Path root)
			throws EditApplicationException {
		final List<EditOperation> operations = new ArrayList<>();
		for (final Monomorphic entry : documentChanges.elementsOf()) {
			if (entry.isMap() == false)
				throw new EditApplicationException("documentChanges holds something that is not an operation: " + entry);

			final String kind = entry.getOrNull("kind").stringOrNull();
			if (kind != null)
				operations.add(parseResourceOperation(kind, entry, root));
			else
				operations.add(parseTextDocumentEdit(entry, root));
		}
		return operations;
	}

	/**
	 * The legacy map form, sorted by path. Sorting is not fidelity - a JSON
	 * object has no order to be faithful to - it is determinism: two runs on the
	 * same answer must produce the same list, if only so a test can state one.
	 * Safe precisely because this form carries no resource operation, so no
	 * entry depends on another having run first.
	 */
	private static List<EditOperation> parseChanges(final Monomorphic changes, final Path root)
			throws EditApplicationException {
		final Map<String, Monomorphic> byPath = new TreeMap<>();
		for (final Map.Entry<String, Monomorphic> entry : changes.asMap().entrySet())
			byPath.put(relativize(entry.getKey(), root), entry.getValue());

		final List<EditOperation> operations = new ArrayList<>();
		for (final Map.Entry<String, Monomorphic> entry : byPath.entrySet())
			operations.add(new FileEdit(entry.getKey(), parseTextEdits(entry.getValue(), entry.getKey())));

		return operations;
	}

	/** A TextDocumentEdit: which document, and the edits it receives. */
	private static FileEdit parseTextDocumentEdit(final Monomorphic entry, final Path root)
			throws EditApplicationException {
		final String uri = entry.getOrNull("textDocument").getOrNull("uri").stringOrNull();
		if (uri == null)
			throw new EditApplicationException("a documentChanges entry names no document: " + entry);

		// The "version" jdtls sends alongside is deliberately ignored: it numbers
		// the revisions of a document *this client opened and has been editing*,
		// and clide never opens one (no textDocument/didOpen - see JdtlsSession).
		// The staleness question it exists to answer is real, but clide answers it
		// on its own ground, by md5 over the whole file, for every file at once -
		// not per document, and not on a counter only an editing session maintains.
		final String path = relativize(uri, root);
		return new FileEdit(path, parseTextEdits(entry.getOrNull("edits"), path));
	}

	private static List<TextEdit> parseTextEdits(final Monomorphic edits, final String path)
			throws EditApplicationException {
		final List<TextEdit> parsed = new ArrayList<>();
		for (final Monomorphic edit : edits.elementsOf())
			parsed.add(parseTextEdit(edit, path));

		return parsed;
	}

	private static TextEdit parseTextEdit(final Monomorphic edit, final String path) throws EditApplicationException {
		if (edit.isMap() == false)
			throw new EditApplicationException("edit on " + path + " is not an object: " + edit);

		if (edit.getOrNull("insertTextFormat").longOrDefault(1) == SNIPPET_FORMAT)
			throw new EditApplicationException("edit on " + path
					+ " is a snippet, whose newText holds placeholders rather than literal text - clide does not expand those");

		final Monomorphic range = edit.getOrNull("range");
		final Monomorphic start = range.getOrNull("start");
		final Monomorphic end = range.getOrNull("end");
		final int startLine = JdtlsResponses.oneBased(JdtlsResponses.lineOf(start));
		final int startColumn = JdtlsResponses.oneBased(JdtlsResponses.characterOf(start));
		final int endLine = JdtlsResponses.oneBased(JdtlsResponses.lineOf(end));
		final int endColumn = JdtlsResponses.oneBased(JdtlsResponses.characterOf(end));
		if (startLine == -1 || startColumn == -1 || endLine == -1 || endColumn == -1)
			throw new EditApplicationException("edit on " + path + " has no usable range: " + edit);

		final String newText = edit.getOrNull("newText").stringOrNull();
		if (newText == null)
			throw new EditApplicationException("edit on " + path + " has no newText: " + edit);

		try {
			return new TextEdit(startLine, startColumn, endLine, endColumn, newText);
		} catch (final IllegalArgumentException e) {
			throw new EditApplicationException("edit on " + path + " is malformed: " + e.getMessage());
		}
	}

	private static ResourceOperation parseResourceOperation(final String kind, final Monomorphic entry, final Path root)
			throws EditApplicationException {
		final ResourceOperationKind resourceKind = ResourceOperationKind.fromLspKind(kind);
		if (resourceKind == null)
			throw new EditApplicationException("unknown resource operation kind: " + kind);

		return switch (resourceKind) {
		case RENAME -> ResourceOperation.rename(relativize(uriOf(entry, "oldUri", kind), root),
				relativize(uriOf(entry, "newUri", kind), root));
		case CREATE -> ResourceOperation.create(relativize(uriOf(entry, "uri", kind), root));
		case DELETE -> ResourceOperation.delete(relativize(uriOf(entry, "uri", kind), root));
		};
	}

	private static String uriOf(final Monomorphic entry, final String key, final String kind)
			throws EditApplicationException {
		final String uri = entry.getOrNull(key).stringOrNull();
		if (uri == null)
			throw new EditApplicationException("resource operation " + kind + " has no " + key + ": " + entry);

		return uri;
	}

	/**
	 * A file: URI as a project-relative path with forward slashes - the form
	 * every path in clide takes outside this package (see the &lt;position&gt;
	 * notation).
	 *
	 * Goes through URI/Path rather than stripping the project's own URI prefix
	 * off the string, unlike JdtlsSession.shortName(): percent-encoding (a
	 * space in a directory name is "%20" in the URI and a space on disk) and
	 * Windows drive letters both survive the round trip this way and neither
	 * survives the other.
	 *
	 * A URI naming something outside the project is refused here rather than
	 * later. WorkspaceEdit.applyTo() checks containment again on the resolved
	 * path, and that repetition is on purpose: this one keeps a foreign path
	 * from ever entering the model, that one keeps it from being written even
	 * if some other producer builds a model by hand.
	 */
	private static String relativize(final String uri, final Path root) throws EditApplicationException {
		final Path file;
		try {
			file = Paths.get(new URI(uri)).toAbsolutePath().normalize();
		} catch (final Exception e) {
			throw new EditApplicationException("edit names something that is not a usable file URI: " + uri);
		}

		if (file.startsWith(root) == false)
			throw new EditApplicationException("edit points outside the project: " + uri);

		return root.relativize(file).toString().replace('\\', '/');
	}

}
