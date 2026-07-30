package clide.command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import clide.annotation.Help;
import clide.annotation.Keyword;
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
