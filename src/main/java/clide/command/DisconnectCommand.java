package clide.command;

import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/**
 * Shared logic behind exit and quit: both stop the jdtls session but leave the
 * clide daemon (and .clide.lock) running for the next connection - see
 * TerminateCommand for the command that also shuts the daemon down. Dispatch
 * is strictly identical between the two (only the @Keyword differs), so - like
 * GotoPositionCommand for the three goto_* commands - this is kept as a shared
 * base with thin concrete subclasses.
 */
public abstract class DisconnectCommand extends Command {

	@Override
	public final boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public final CommandResult executeCommand(final ClideContext context, final String... params) {
		context.stopSession();
		context.requestDisconnect();
		return CommandResult.ok("");
	}

}
