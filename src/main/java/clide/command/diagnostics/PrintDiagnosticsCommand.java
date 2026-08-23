package clide.command.diagnostics;


import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.jdtls.JdtlsSession;

/**
 * Reports the diagnostics collected by the last build() of the project this
 * daemon owns (built automatically at daemon startup - see CLAUDE.md).
 */
public class PrintDiagnosticsCommand extends Command {

	@Keyword("print_diagnostics")
	@Help("Reports diagnostics from the current project's last build: <all> lists everything, <errors> only errors.")
	@Param(type = ParamType.SINGLE_LINE, description = "Filter: all or errors")
	@Manual("""
			NAME
				print_diagnostics - report diagnostics from the last build

			SYNOPSIS
				print_diagnostics <filter>

			DESCRIPTION
				Reports the diagnostics jdtls collected during this project's
				last build - the one that runs automatically at daemon
				startup, or the most recent rebuild since then.
				print_diagnostics never triggers a build itself, it only
				reports what the last one already found, so a file edited
				since is still described as it was at that build. Use
				rebuild to compile again and get a current answer.
				<filter> is one of two literal values: "all" reports every
				diagnostic, "errors" reports only those at error severity.

			ERRORS
				<filter> must be exactly "all" or "errors" - anything else,
				including a typo, is rejected (INVALID_ENUM_VALUE) rather
				than silently treated as "all".

			SEE ALSO
				rebuild(1)
			""")
	public PrintDiagnosticsCommand() {

	}

	/**
	 * Being about the last build <i>is</i> this command's contract - it
	 * re-displays what that build said, without recompiling, and never claimed
	 * to describe the current disk. See Command.needsFreshModel().
	 */
	@Override
	public boolean needsFreshModel() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("filter", params[0], "all", "errors");
		if (rejected != null)
			return rejected;

		final JdtlsSession session = context.getCurrentSession();

		final boolean errorsOnly = params[0].equals("errors");
		return CommandResult
				.ok(new CommandPayload.Diagnostics(session.diagnosticsReport(errorsOnly, context.getMaxResults())));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Diagnostics found -> DiagnosticsRendering.render(found.report());
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
