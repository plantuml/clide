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
 * Commits a transaction - see TransactionStack, CLAUDE.md.
 */
public class CommitTransactionCommand extends Command {

	@Keyword("commit_transaction")
	@Help("Commits <transaction id> - and any of its still-open sub-transactions - keeping every change it recorded.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Manual("""
			NAME
				commit_transaction - commit a transaction, keeping its changes

			SYNOPSIS
				commit_transaction <transaction id>

			DESCRIPTION
				Commits <transaction id>. Every file modification recorded
				under it is kept as-is on disk; only the backups clide was
				holding onto (in case of a rollback) are discarded.

				If <transaction id> still has open sub-transactions, they
				are committed first, deepest one first, each folding its
				own recorded changes into the transaction it was opened
				under - so committing "$refactor_foo" while
				"$refactor_foo$part1" is still open commits part1 first,
				then "$refactor_foo" itself.

				If <transaction id> is itself a sub-transaction, committing
				it folds its recorded changes into its own parent
				transaction instead of discarding them outright - only
				committing a root transaction (no "$parent" of its own)
				actually discards the backups for good.

			ERRORS
				Refused if <transaction id> is not currently open.

			SEE ALSO
				open_transaction(1), rollback_transaction(1), diff_transaction(1)
			""")
	public CommitTransactionCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		try {
			context.getTransactions().commit(id);
		} catch (final IOException | IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}
		return CommandResult.ok("Transaction " + id + " committed.");
	}

}
