package clide.command;

import java.io.IOException;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/**
 * Rolls back a transaction - see TransactionStack, CLAUDE.md.
 */
public class RollbackTransactionCommand extends Command {

	@Keyword("rollback_transaction")
	@Help("Rolls back <transaction id> - and any of its still-open sub-transactions - undoing every change it recorded.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Manual("""
			NAME
				rollback_transaction - roll back a transaction, undoing its changes

			SYNOPSIS
				rollback_transaction <transaction id>

			DESCRIPTION
				Rolls back <transaction id>: every file it (or any of its
				still-open sub-transactions) recorded a modification for is
				restored to the state it had right before that
				transaction's subtree first touched it - deleted outright
				if the subtree is what created it.

				If <transaction id> still has open sub-transactions, they
				are rolled back first, most-recently-opened one first, so
				each layer of edits is undone in the reverse order it was
				made - the same way a stack unwinds.

			ERRORS
				Refused if <transaction id> is not currently open.

			SEE ALSO
				open_transaction(1), commit_transaction(1), diff_transaction(1), restore_file(1)
			""")
	public RollbackTransactionCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		try {
			context.getTransactions().rollback(id);
		} catch (final IOException | IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}
		return CommandResult.ok("Transaction " + id + " rolled back.");
	}

}
