package clide.command.diagnostics;


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
import clide.jdtls.JdtlsSession;

/**
 * Recompiles the project and reports the diagnostics of that build - the
 * "does what I just wrote actually compile" question, which was the first
 * priority this project set itself and the one thing no command answered.
 *
 * Until now the only build a client could ever see was the one the daemon runs
 * once at startup: print_diagnostics replays that build's diagnostics and never
 * triggers a new one, so a file edited after the daemon came up kept reporting
 * the state it had at boot. The only way to get a fresh answer was terminate
 * plus a reconnection - a full jdtls restart, a minute on a project the size of
 * PlantUML. rebuild is that answer without the restart.
 */
public class RebuildCommand extends Command {

	@Keyword("rebuild")
	@Help("Recompiles the project and reports the diagnostics of that build: <all> lists everything, <errors> only errors.")
	@Param(type = ParamType.SINGLE_LINE, description = "Filter: all or errors")
	@Manual("""
			NAME
				rebuild - recompile the project and report what the compiler said

			SYNOPSIS
				rebuild <filter>

			DESCRIPTION
				Recompiles the whole project through jdtls, then reports the
				diagnostics that build produced - in the same format, and
				with the same <filter> values, as print_diagnostics: "all"
				reports every diagnostic, "errors" only those at error
				severity. Both steps in one command, because the question
				being asked is a single one ("did my change compile?") and
				splitting it over two round trips only costs a round trip.

				Files modified on disk since jdtls last looked are
				picked up:
				jdtls is told what changed before the build runs, so an edit
				made from outside clide counts just as much as one made
				through it. Deleted and newly created .java files count too.

				Use print_diagnostics instead to re-read the last build's
				diagnostics without paying for a build.

			ERRORS
				As with print_diagnostics, only the exact literal "errors"
				filters down to error-severity diagnostics; any other value
				for <filter> reports everything. rebuild never rejects its
				argument. A build that fails outright - as opposed to one
				that succeeds while reporting compile errors - is reported
				as an error, and the previous build's diagnostics are left
				untouched.

			SEE ALSO
				print_diagnostics(1)
			""")
	public RebuildCommand() {

	}

	/**
	 * The cure, so never the patient: refusing rebuild on a stale model would
	 * leave no way at all of becoming un-stale - see Command.needsFreshModel().
	 */
	@Override
	public boolean needsFreshModel() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final long startedAt = System.currentTimeMillis();
		final int refreshed;
		try {
			refreshed = session.refreshChangedFiles();
			session.build();
		} catch (final Exception e) {
			// The build itself broke, as opposed to succeeding while reporting compile
			// errors - the previous build's diagnostics are left untouched, so
			// print_diagnostics still describes the last build that did complete.
			return CommandResult.error(ErrorCode.BUILD_FAILED, "rebuild failed: " + e.getMessage());
		}
		final long elapsed = System.currentTimeMillis() - startedAt;

		final boolean errorsOnly = params[0].equals("errors");
		return CommandResult.ok(new CommandPayload.Rebuild(refreshed, elapsed,
				session.diagnosticsReport(errorsOnly, context.getMaxResults())));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Rebuild built -> "rebuild: " + built.changedFiles()
				+ " file(s) changed since jdtls last looked, rebuilt in " + built.elapsedMillis() + " ms\n"
				+ DiagnosticsRendering.render(built.report());
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
