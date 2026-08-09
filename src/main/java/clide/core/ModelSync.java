package clide.core;

import java.io.IOException;

import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.jdtls.JdtlsSession;

/**
 * Keeping jdtls' model in step with the files on disk, at the two moments
 * clide knows it might have drifted: just before a command questions jdtls,
 * and just after a command has written to the project itself.
 *
 * <h2>Why this exists at all</h2>
 *
 * jdtls' model is an Eclipse workspace, not a view of the filesystem: it only
 * learns of a change made outside its own editing session when told, and clide
 * never opens documents (see JdtlsSession). CLAUDE.md has always required a
 * rebuild after an edit made outside clide, and nothing ever checked it - so a
 * caller who forgot got a plausible answer about a project that had moved on:
 * find_reference missing the usage added a minute earlier, which reads exactly
 * like a symbol with no usage there.
 *
 * <h2>Resynchronise rather than refuse</h2>
 *
 * The first version of this refused, with STALE_MODEL, and told the caller to
 * run rebuild. Safe, and needlessly harsh: measurement showed that a plain
 * workspace/didChangeWatchedFiles notification - no build of any kind - is
 * enough to bring the model back in step, for a created file, an edited one, a
 * deleted one, for the "out of sync" refusal textDocument/rename would
 * otherwise answer, and even for the diagnostics (see
 * JdtlsSession.refreshChangedFiles(), and JDTLS.md for the numbers). On the
 * PlantUML checkout that costs about 1.5 s where a full rebuild costs 14.5 s,
 * and where refusing cost the caller a whole round trip plus that same rebuild
 * anyway.
 *
 * So STALE_MODEL survives, and should now be almost unreachable: it is what a
 * caller sees when the resynchronisation itself failed, not when the project
 * merely moved.
 *
 * <h2>What it costs when nothing moved</h2>
 *
 * One full-project file scan per command - md5 over every .java file, read in
 * parallel; about 180 ms on PlantUML's 3633 sources, cache-warm, on two cores.
 * Cheaper is possible and deliberately not done yet: on that same checkout the
 * directory walk alone costs ~40 ms and stat-ing every file for its mtime and
 * size ~12 ms, so a "nothing looks touched" pre-check would answer the common
 * case in a third of the time. It is not free of meaning, though - it would
 * trade Snapshot's content-based definition of "changed" (see its class doc)
 * for a timestamp one, and miss an edit that preserved both mtime and size.
 * Worth doing on measured need, not on principle.
 */
public final class ModelSync {

	private ModelSync() {
	}

	/**
	 * Brings the model in step before command runs, and returns null - or the
	 * refusal to answer with when even that could not be done.
	 *
	 * Skipped for a command that declares needsFreshModel() false, which today
	 * means rebuild (it resynchronises and builds on its own, so doing it here
	 * too would pay the scan twice) and print_diagnostics (being about the last
	 * build is precisely its contract).
	 */
	public static CommandResult beforeCommand(final ClideContext context, final Command command) {
		if (command.needsFreshModel() == false)
			return null;

		try {
			context.getCurrentSession().refreshChangedFiles();
			return null;
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.STALE_MODEL, "files changed since jdtls last saw this project, and "
					+ command.getKeyword() + " could not bring it back in step: " + e.getMessage());
		}
	}

	/**
	 * Tells jdtls about files a command has just written, and says nothing when
	 * it cannot.
	 *
	 * Best-effort on purpose. The callers are rollback_transaction and
	 * restore_file, which put files back the way they were and must succeed at
	 * that whatever the state of the session - they are, after all, what a
	 * caller reaches for when things have gone wrong. Neither declares
	 * needsJdtlsSession(), so the session may legitimately be stopped here.
	 *
	 * Nothing is lost by staying quiet: beforeCommand() runs before the next
	 * command that questions jdtls and will notice the same files then. This
	 * only makes the answer right <i>immediately</i> after the restore - which
	 * matters most for print_diagnostics, the one command that skips
	 * beforeCommand() and would otherwise keep reporting the diagnostics of the
	 * very state the rollback just undid.
	 *
	 * commit_transaction deliberately does not call this: committing changes no
	 * file, it only drops the marker directory, so there is nothing jdtls has
	 * not already been told.
	 */
	public static void afterRestore(final ClideContext context) {
		final JdtlsSession session = context.getCurrentSession();
		if (session == null || session.isReady() == false)
			return;

		try {
			session.refreshChangedFiles();
		} catch (final IOException e) {
			// The restore itself succeeded and that is what was asked for. The next
			// command to question jdtls resynchronises anyway - see beforeCommand().
		}
	}

}
