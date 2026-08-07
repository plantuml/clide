package clide.command.session;

import java.util.List;

import clide.command.answer.CommandResult;
import clide.command.answer.Warning;
import clide.command.answer.WarningCode;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Shared logic behind exit and quit: both stop the jdtls session but leave the
 * clide daemon (and .clide.lock) running for the next connection - see
 * TerminateCommand for the command that also shuts the daemon down. Dispatch
 * is strictly identical between the two (only the @Keyword differs), so this
 * is kept as a shared abstract base with thin concrete subclasses (ExitCommand,
 * QuitCommand) rather than duplicating the same logic twice.
 *
 * Unlike terminate, exit/quit never touch TransactionStack: the whole point
 * of leaving the daemon up is that the next connection can pick up right
 * where this one left off, still-open transactions included. If any are
 * open, a warning is appended instead - purely informational, nothing here
 * is blocked by it.
 */
public abstract class DisconnectCommand extends Command {

	@Override
	public final boolean needsJdtlsSession() {
		return false;
	}

	/**
	 * Never a Lua function: "exit"/"quit" stop the jdtls session, which a script
	 * is in the middle of using - see Command.isScriptable().
	 */
	@Override
	public final boolean isScriptable() {
		return false;
	}

	@Override
	public final CommandResult executeCommand(final ClideContext context, final String... params) {
		context.requestDisconnect();

		final List<String> open = context.getTransactions().openIds();
		if (open.isEmpty())
			return CommandResult.empty();

		// A real Warning now rather than a sentence starting with "Warning:" - same
		// information, but a client can tell it apart from the answer without
		// matching on a prefix, and the result stays OK because nothing was blocked.
		return CommandResult.empty()
				.withWarning(Warning.of(WarningCode.TRANSACTIONS_STILL_OPEN,
						"transaction(s) still open, unaffected by exit/quit - reconnect to "
								+ "commit_transaction/rollback_transaction them: " + String.join(", ", open)));
	}

}
