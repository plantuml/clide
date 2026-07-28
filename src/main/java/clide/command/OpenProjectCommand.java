package clide.command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;

/**
 * Opens (or re-focuses) a Java project: starts a dedicated jdtls if none is
 * running yet for that path (one session per project, several can be open at
 * once), otherwise reuses the existing one, triggers a full build and reports
 * the resulting diagnostics. Becomes the "current" project for
 * print_diagnostics.
 */
public class OpenProjectCommand extends Command {

	@Keyword("open_project")
	@Help("Opens a Java project at <project path>: starts/reuses its jdtls session, builds it, reports diagnostics.")
	@Param("Project path")
	public OpenProjectCommand() {
		// Constructeur
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String pathArgument = params[0];
		if (pathArgument.isEmpty())
			return CommandResult.error("Usage: open_project <path>");

		final Path projectRoot = Paths.get(pathArgument).toAbsolutePath().normalize();
		if (Files.isDirectory(projectRoot) == false)
			return CommandResult.error("Not a directory: " + projectRoot);

		final StringBuilder output = new StringBuilder();
		JdtlsSession session = context.getSessions().get(projectRoot);
		try {
			if (session == null) {
				output.append("Starting jdtls for ").append(projectRoot).append(" ...\n");
				final JdtlsLauncher launcher = new JdtlsLauncher(jdtlsHome());
				session = new JdtlsSession(launcher, projectRoot);
				context.getSessions().put(projectRoot, session);
			}
			if (session.isReady() == false)
				session.start();

			session.build();
			context.setCurrentSession(session);
			output.append("open_project ok for ").append(projectRoot).append('\n');
			output.append(captureDiagnostics(session, true));
			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error("open_project failed for " + projectRoot + ": " + e.getMessage());
		}
	}

	private String captureDiagnostics(final JdtlsSession session, final boolean printOnlyError) {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
			session.reportDiagnostics(out, printOnlyError);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private Path jdtlsHome() {
		final String override = System.getenv("CLIDE_JDTLS_HOME");
		if (override != null)
			return Paths.get(override);

		return Paths.get("jdtls");
	}

}
