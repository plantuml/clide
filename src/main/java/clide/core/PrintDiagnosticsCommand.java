package clide.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import clide.JdtlsSession;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;

/** Reports the diagnostics collected by the last build() of the current project (see open_project). */
public class PrintDiagnosticsCommand extends Command {

	@Keyword("print_diagnostics")
	@Help("Reports diagnostics from the current project's last build: <all> lists everything, <errors> only errors.")
	@Param("all | errors")
	public PrintDiagnosticsCommand() {
		// Constructeur
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();
		if (session == null)
			return CommandResult.error("No project open — use open_project first");

		final boolean errorsOnly = params[0].equals("errors");
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
			session.reportDiagnostics(out, errorsOnly);
		}
		return CommandResult.ok(buffer.toString(StandardCharsets.UTF_8).strip());
	}

}
