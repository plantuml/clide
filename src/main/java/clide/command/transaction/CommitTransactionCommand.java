package clide.command.transaction;

import java.io.IOException;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;

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
				Commits <transaction id>. Nothing changes on disk - every
				file it touched is already exactly as it should be - only
				the restore point clide was holding onto (in case of a
				rollback) is discarded, for <transaction id> and any
				sub-transaction of it.

				If <transaction id> still has open sub-transactions, they
				are committed first, deepest one first - so committing
				"$refactor_foo" while "$refactor_foo$part1" is still open
				commits part1 first, then "$refactor_foo" itself. Either
				way the outcome is the same: their changes simply stay.

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
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}
		return CommandResult.ok(new CommandPayload.Transaction(id, CommandPayload.Transaction.Action.COMMITTED, ""));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return TransactionRendering.render(getKeyword(), result);
	}

}
