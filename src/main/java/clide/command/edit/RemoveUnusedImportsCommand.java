package clide.command.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.SourceFile;
import clide.core.SourceFiles;
import clide.jdtls.JdtlsSession;
import clide.model.Listing;

/**
 * Deletes every unused import line jdtls flagged, from every project file
 * whose (project-relative, forward-slash) path matches &lt;path regex&gt; -
 * requires an open transaction.
 *
 * <h2>Detection, not parsing</h2>
 *
 * This command never parses a single Java file to work out whether an import
 * is used: it reads the answer straight out of jdtls' own diagnostics, the
 * same ones print_diagnostics prints, filtered to jdt's own problem id for
 * "The import ... is never used" (see JdtlsSession.unusedImportLines()). That
 * id was found empirically, by printing a raw diagnostic and reading its
 * "code" field back - see HISTORY.md.
 *
 * Only <i>unused</i> imports are touched. Reordering, regrouping, or
 * collapsing a wildcard is jdtls' broader source.organizeImports, which this
 * command deliberately does not call - a caller who wants that keeps doing it
 * their own way, and this command's edit stays small enough to trust without
 * reading a diff line by line.
 *
 * <h2>Matched, but nothing to remove, is not an error</h2>
 *
 * &lt;path regex&gt; matching zero files is NO_FILES_FOUND - almost always a
 * typo'd regex, the same failure search_regex's own &lt;path regex&gt; can
 * hit. A regex that matches real files which simply have no unused import is
 * a completely different thing and is reported as "0 changed", not refused:
 * see CommandPayload.RemoveUnusedImports.
 *
 * <h2>Writing needs no Transaction API call</h2>
 *
 * Unlike RenameCommand, which applies a WorkspaceEdit jdtls computed, this
 * command edits files itself and simply calls SourceFiles.writeLines() - no
 * backupBeforeModification() or similar is needed. A transaction's opening
 * Snapshot already covers every .java source file the moment the transaction
 * opens (see Transaction), so the machinery that lets rollback_transaction
 * undo this edit is already in place before this command ever runs; all that
 * is required of the command itself is needsOpenTransaction() below.
 */
public class RemoveUnusedImportsCommand extends Command {

	/**
	 * What an import line clide is willing to delete has to look like -
	 * checked again here, against the file as read right now, even though the
	 * line number came from jdtls' own diagnostics. Defensive on purpose: a
	 * line that does not match this shape is left alone rather than deleted on
	 * faith that jdtls' line number and the file on disk still agree.
	 *
	 * Group 1 is what gets reported as the removed import - "static " kept
	 * when present, since dropping it would report a name that does not
	 * actually name what was removed.
	 */
	private static final Pattern IMPORT_LINE = Pattern
			.compile("^\\s*import\\s+((?:static\\s+)?[\\w.]+(?:\\.\\*)?)\\s*;\\s*$");

	@Keyword("remove_unused_imports")
	@Help("Removes every unused import jdtls flagged from every file whose path matches <path regex> - requires an open transaction.")
	@Param(type = ParamType.REGEX, description = "Path regex")
	@Manual("""
			NAME
				remove_unused_imports - delete unused import lines

			SYNOPSIS
				remove_unused_imports <path regex>

			DESCRIPTION
				Walks every .java file under the project, keeps the ones
				whose project-relative path (forward slashes, exactly
				like search_regex's <path regex> and a <position>'s
				file path) matches <path regex>, and deletes every
				import line jdtls' last build flagged as unused in one
				of them.

				Detection comes straight from jdtls' own diagnostics -
				the same ones print_diagnostics prints - never from
				clide parsing the file itself. Only imports jdtls
				actually flagged as unused are touched: nothing is
				reordered, regrouped, or collapsed, and a wildcard
				import already in use is left exactly as it is.

				The answer names how many files <path regex> matched
				and, of those, how many were actually changed - a file
				that matched but had nothing to remove is not an
				error, and is not listed among the changed files.
				Each changed file is listed with the import(s) it
				lost, in the order they appeared in the file.

				jdtls is told about the edit immediately, and the
				resulting error count is part of the answer, exactly
				like rename - print_diagnostics prints the detail
				without recompiling.

				Requires an open transaction: this writes files, and a
				transaction is what undoes them. Nothing is committed
				here - commit_transaction or rollback_transaction
				decides, and diff_transaction shows any single file
				first.

			ERRORS
				INVALID_REGEX - <path regex> does not compile.

				NO_FILES_FOUND - <path regex> matched no file under
				the project. Distinct from matching files that simply
				had nothing to remove, which is not an error.

				NO_OPEN_TRANSACTION - see open_transaction.

			SEE ALSO
				search_regex(1), print_diagnostics(1), rename(1),
				open_transaction(1), diff_transaction(1),
				rollback_transaction(1)
			""")
	public RemoveUnusedImportsCommand() {

	}

	/** Writes to the project - see the class doc. */
	@Override
	public boolean needsOpenTransaction() {
		return true;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Pattern pathPattern;
		try {
			pathPattern = Pattern.compile(params[0]);
		} catch (final PatternSyntaxException e) {
			return CommandResult.error(ErrorCode.INVALID_REGEX, "Invalid regex: " + e.getMessage());
		}

		final Path projectRoot = context.getProjectRoot();
		final List<String> matchedPaths = new ArrayList<>();
		try {
			for (final SourceFile source : context.getFilesRepository().currentSourceFiles()) {
				final String displayPath = displayPath(Path.of(source.sourceFilePath()), projectRoot);
				if (pathPattern.matcher(displayPath).find())
					matchedPaths.add(displayPath);
			}
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "remove_unused_imports failed: " + e.getMessage());
		}
		Collections.sort(matchedPaths);

		if (matchedPaths.isEmpty())
			return CommandResult.error(ErrorCode.NO_FILES_FOUND,
					"No file under the project matches '" + params[0] + "'");

		final JdtlsSession session = context.getCurrentSession();
		final Map<String, List<Integer>> unusedByFile = session.unusedImportLines();

		final List<CommandPayload.RemoveUnusedImports.FileChange> changed = new ArrayList<>();
		for (final String path : matchedPaths) {
			final List<Integer> candidateLines = unusedByFile.get(path);
			if (candidateLines == null || candidateLines.isEmpty())
				continue;

			final List<String> removedImports;
			try {
				removedImports = removeUnusedImportLines(projectRoot.resolve(path), candidateLines);
			} catch (final IOException e) {
				return CommandResult.error(ErrorCode.IO_FAILED,
						"remove_unused_imports failed on '" + path + "': " + e.getMessage());
			}

			if (removedImports.isEmpty() == false)
				changed.add(new CommandPayload.RemoveUnusedImports.FileChange(path, removedImports));
		}

		final int errorCount;
		try {
			// Cheap even when nothing changed - refreshChangedFiles() diffs a fresh
			// Snapshot against the last synced one and returns early when the delta is
			// empty, sending nothing to jdtls at all. See RenameCommand for why this,
			// alone, is enough to bring the model back in step.
			session.refreshChangedFiles();
			errorCount = session.diagnosticsReport(true, context.getMaxResults()).errorCount();
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED,
					"remove_unused_imports failed while refreshing the model: " + e.getMessage());
		}

		return CommandResult.ok(new CommandPayload.RemoveUnusedImports(matchedPaths.size(),
				Listing.of(changed, context.getMaxResults()), errorCount));
	}

	/**
	 * Deletes candidateLines (1-based, as jdtls reported them) from file,
	 * keeping only the ones that still look like a plain import statement -
	 * see IMPORT_LINE - and writes the result back if anything was actually
	 * removed. Deletes bottom-to-top so removing one candidate line never
	 * shifts the line number of another still waiting to be checked.
	 *
	 * Returns the removed import names in file order (ascending line number),
	 * not deletion order - what a reader of the answer expects.
	 *
	 * Package-private rather than private: this is the one piece of this
	 * command that needs no jdtls session to exercise directly - everything
	 * else here is either checked before the session is ever touched
	 * (the regex, NO_FILES_FOUND) or only meaningful against a live server, so
	 * only this is worth a dedicated unit test - see RemoveUnusedImportsCommandTest.
	 */
	static List<String> removeUnusedImportLines(final Path file, final List<Integer> candidateLines)
			throws IOException {
		final List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));

		final Map<Integer, String> toRemove = new TreeMap<>();
		for (final int lineNumber : candidateLines) {
			if (lineNumber < 1 || lineNumber > lines.size())
				continue;

			final Matcher matcher = IMPORT_LINE.matcher(lines.get(lineNumber - 1));
			if (matcher.matches())
				toRemove.put(lineNumber, matcher.group(1));
		}

		if (toRemove.isEmpty())
			return List.of();

		final List<Integer> descending = new ArrayList<>(toRemove.keySet());
		Collections.sort(descending, Collections.reverseOrder());
		for (final int lineNumber : descending)
			lines.remove(lineNumber - 1);

		SourceFiles.writeLines(file, lines);
		return new ArrayList<>(toRemove.values());
	}

	/**
	 * file as the client should see it: relative to projectRoot, forward
	 * slashes - the same shape search_regex's own displayPath() produces, and
	 * the same shape a &lt;position&gt; parameter's file path expects.
	 */
	private static String displayPath(final Path file, final Path projectRoot) {
		final Path relative = file.startsWith(projectRoot) ? projectRoot.relativize(file) : file;
		return relative.toString().replace('\\', '/');
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.RemoveUnusedImports removed -> {
			final Listing<CommandPayload.RemoveUnusedImports.FileChange> changed = removed.changedFiles();
			if (changed.totalCount() == 0) {
				yield "remove_unused_imports: " + removed.matchedFileCount() + " file(s) matched, nothing to remove";
			}

			final StringBuilder out = new StringBuilder();
			out.append("remove_unused_imports: ").append(removed.matchedFileCount()).append(" file(s) matched, ")
					.append(changed.summarize("file")).append(" changed");
			for (final CommandPayload.RemoveUnusedImports.FileChange file : changed.items())
				out.append('\n').append(file.path()).append(": removed ")
						.append(String.join(", ", file.removedImports()));

			out.append("\nrebuilt: ").append(removed.errorCount()).append(" error(s)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
