package clide.command.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import clide.core.FileChange;
import clide.core.NamedSnapshots;
import clide.core.Snapshot;
import clide.model.ChangedFile;
import clide.model.Listing;

/**
 * What moved since a named snapshot - the comparison half of snapshot.
 *
 * It does not move the snapshot: asking twice answers the same question twice,
 * and the answer is cumulative since the snapshot was taken. Taking a new one
 * under the same name is how to start over.
 */
public class ChangedSinceCommand extends Command {

	@Keyword("changed_since")
	@Help("Lists the files created, changed or deleted since the snapshot <name>, with their md5 before and after.")
	@Param(type = ParamType.SINGLE_LINE, description = "Name")
	@Manual("""
			NAME
				changed_since - what moved since a snapshot

			SYNOPSIS
				changed_since <name>

			DESCRIPTION
				Looks again at the files snapshot <name> covered, through the
				same glob, and lists every one that was created, changed or
				deleted since it was taken: its path relative to the project,
				how it moved, and the md5 of its content before and after
				(empty when it did not exist then, or no longer does). A file
				rewritten with the very same bytes is not a change: what is
				compared is the content, never the modification time.

				A file created since, or one that now matches the glob without
				having before, is listed as created; one that no longer matches
				or no longer exists, as deleted.

				It does not move the snapshot: asking again answers again, from
				the same starting point. To start over, take the snapshot again
				under the same name. Listings are capped like every other
				(see set_max_results); the total is always exact.

			ERRORS
				Refused if no snapshot of that name exists; the message lists
				the names that do.

			SEE ALSO
				snapshot(1), set_max_results(1)
			""")
	public ChangedSinceCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0].strip();
		final NamedSnapshots.Named named = context.getNamedSnapshots().get(id);
		if (named == null) {
			final List<String> known = context.getNamedSnapshots().ids();
			return CommandResult.error(ErrorCode.NO_SUCH_SNAPSHOT, "no snapshot named '" + id + "'"
					+ (known.isEmpty() ? " - none has been taken" : " - taken: " + String.join(", ", known)));
		}

		final Snapshot live;
		try {
			live = Snapshot.build(context.getFilesRepository(), named.matcher());
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED,
					"could not read the files matching " + named.glob() + ": " + e.getMessage());
		}

		final Path root = context.getProjectRoot();
		final List<ChangedFile> changes = new ArrayList<>();
		for (final FileChange change : live.compareWithPreviousSnapshot(named.snapshot()).changes()) {
			final Path absolute = Path.of(change.path());
			final String before = named.snapshot().md5Of(absolute);
			final String after = live.md5Of(absolute);
			changes.add(new ChangedFile(root.relativize(absolute).toString().replace('\\', '/'), change.type(),
					before == null ? "" : before, after == null ? "" : after));
		}

		return CommandResult.ok(new CommandPayload.Changes(id, named.glob(), live.size(),
				Listing.of(changes, context.getMaxResults())));
	}

	/** "before -> after" for a change, the one md5 there is for a creation or a deletion. */
	private static String md5s(final ChangedFile file) {
		return switch (file.type()) {
		case CREATED -> file.md5After();
		case DELETED -> file.md5Before();
		case CHANGED -> file.md5Before() + " -> " + file.md5After();
		};
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Changes moved -> {
			if (moved.changes().totalCount() == 0)
				yield "changed_since: nothing moved since " + moved.id() + " (" + moved.fileCount()
						+ " file(s) match " + moved.glob() + ")";

			final StringBuilder out = new StringBuilder();
			out.append("changed_since: ").append(moved.changes().summarize("file")).append(" since ")
					.append(moved.id()).append(" (").append(moved.fileCount()).append(" match ")
					.append(moved.glob()).append(" now)");
			for (final ChangedFile file : moved.changes().items())
				out.append('\n').append('[').append(file.type().name().toLowerCase()).append("] ")
						.append(file.path()).append("  ").append(md5s(file));

			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
