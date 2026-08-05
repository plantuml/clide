package clide.command.session;

import java.util.List;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.command.result.CommandResult;
import clide.command.result.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;

/** Stops the jdtls session and shuts down the clide daemon entirely. */
public class TerminateCommand extends Command {

	@Keyword("terminate")
	@Help("Stops the jdtls session and shuts down the clide daemon - refused while any transaction is still open.")
	@Manual("""
			NAME
				terminate - stop the jdtls session and shut down the daemon

			SYNOPSIS
				terminate

			DESCRIPTION
				Stops the project's jdtls session, the same first step as
				exit/quit, then goes further and shuts the clide daemon
				itself down, releasing .clide.lock. The next "clide <project
				path>" run for this project starts a fresh daemon - and, in
				turn, a fresh jdtls session - rather than reconnecting to
				this one. Use exit or quit instead when only the session,
				not the daemon, should be freed - and when a transaction
				should be left open for later, since terminate never leaves
				one behind (see ERRORS).

			ERRORS
				Refused, with no effect, while any transaction is still
				open: exit/quit leave that state for the next connection to
				resume, on purpose, but terminate ends the daemon process
				for good, and a transaction with nobody left to commit or
				roll it back is exactly the dirty .clide/transactions state
				refuseIfDirty() exists to catch at the next startup (see
				TransactionStack, CLAUDE.md) - reserved for an actual daemon
				crash, not a deliberate shutdown. commit_transaction or
				rollback_transaction every open transaction first.

			SEE ALSO
				exit(1), quit(1), commit_transaction(1), rollback_transaction(1)
			""")
	public TerminateCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final List<String> open = context.getTransactions().openIds();
		if (open.isEmpty() == false)
			return CommandResult.error(ErrorCode.TERMINATE_REFUSED,
					"Refusing to terminate while transaction(s) are still open - commit_transaction or "
							+ "rollback_transaction each one first: " + String.join(", ", open));

		context.stopSession();
		context.requestShutdown();
		return CommandResult.empty();
	}

}
