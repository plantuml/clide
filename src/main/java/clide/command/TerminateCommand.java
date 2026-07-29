package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/** Stops the jdtls session and shuts down the clide daemon entirely. */
public class TerminateCommand extends Command {

	@Keyword("terminate")
	@Help("Stops the jdtls session and shuts down the clide daemon.")
	public TerminateCommand() {
		// Constructeur
	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		context.stopSession();
		context.requestShutdown();
		return CommandResult.ok("");
	}

}
