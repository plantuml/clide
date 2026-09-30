package clide.command.snapshot;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
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
import clide.core.NamedSnapshots;
import clide.core.Snapshot;

/**
 * Takes a named snapshot of the files a glob matches, to be compared later with
 * changed_since - see NamedSnapshots.
 *
 * Reads nothing from jdtls and tells it nothing: whatever the glob matches
 * (.svg reference files, say) is none of its business, and the model it keeps of
 * the .java sources is not touched. The signatures go through the same md5
 * store as every other snapshot, so the content behind each one stays
 * available.
 */
public class SnapshotCommand extends Command {

	@Keyword("snapshot")
	@Help("Takes a named snapshot of the files matching <glob>, to compare later with changed_since - <name> then <glob>.")
	@Param(type = ParamType.SINGLE_LINE, description = "Name")
	@Param(type = ParamType.SINGLE_LINE, description = "Glob")
	@Manual("""
			NAME
				snapshot - remember the state of a set of files

			SYNOPSIS
				snapshot <name> <glob>

			DESCRIPTION
				Signs (md5 of the content) every file under the project whose
				path, relative to the project root, matches <glob>, and keeps
				the result under <name>. changed_since <name> then says which
				of those files were created, changed or deleted since.

				<glob> is Java's glob syntax, applied to the path relative to
				the project root, written with '/': "src/test/resources/**.svg"
				covers every .svg under that folder, at any depth; "*.md" only
				the ones at the top. Any file type, not just .java. .git,
				.gradle and .clide are never looked into.

				Taking a snapshot under a name already in use replaces the
				earlier one, and says so. Snapshots live as long as the daemon,
				across connections, and are never written to disk. They have
				nothing to do with jdtls: they are not what rebuild compares, and
				a file they cover changing never makes the model stale.

				Matching nothing is not an error - a snapshot of no file is how
				to notice that files appear later - but the count is printed, and
				0 is far more often a glob that does not match than a folder
				that is empty.

			ERRORS
				<name> is 1 to 64 of letters, digits, '_', '-' and '.'. A
				<glob> that does not parse is refused, naming the fault.

			SEE ALSO
				changed_since(1)
			""")
	public SnapshotCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0].strip();
		final String glob = params[1].strip();

		if (NamedSnapshots.isValidId(id) == false)
			return CommandResult.error(ErrorCode.INVALID_SNAPSHOT_ID, "'" + id + "' is not a valid snapshot name - "
					+ "expected 1 to 64 of letters, digits, '_', '-' or '.'");

		final PathMatcher matcher;
		try {
			matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
		} catch (final PatternSyntaxException | UnsupportedOperationException e) {
			return CommandResult.error(ErrorCode.INVALID_GLOB, "'" + glob + "' is not a valid glob: " + e.getMessage());
		}

		final Snapshot snapshot;
		try {
			snapshot = Snapshot.build(context.getFilesRepository(), matcher);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "could not read the files matching " + glob + ": " + e.getMessage());
		}

		final NamedSnapshots.Named replaced = context.getNamedSnapshots().put(id,
				new NamedSnapshots.Named(glob, matcher, snapshot));
		return CommandResult
				.ok(new CommandPayload.Snapshotted(id, glob, snapshot.size(), replaced != null));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Snapshotted taken -> "snapshot: " + taken.id() + " = " + taken.fileCount()
				+ " file(s) matching " + taken.glob() + (taken.replaced() ? " (replaced the earlier " + taken.id() + ")" : "");
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
