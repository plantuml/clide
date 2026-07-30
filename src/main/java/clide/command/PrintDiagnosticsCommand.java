package clide.command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
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
				last build - the one that runs once, automatically, at
				daemon startup. print_diagnostics never triggers a new
				build itself, it only reports what the last one already
				found. <filter> is one of two literal values: "all" reports
				every diagnostic, "errors" reports only those at error
				severity.

			ERRORS
				Only the exact literal "errors" filters down to
				error-severity diagnostics; any other value for <filter> -
				including "all", or a typo - reports everything.
				print_diagnostics never rejects its argument.
			""")
	public PrintDiagnosticsCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final boolean errorsOnly = params[0].equals("errors");
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
			session.reportDiagnostics(out, errorsOnly);
		}
		return CommandResult.ok(buffer.toString(StandardCharsets.UTF_8).strip());
	}

}
