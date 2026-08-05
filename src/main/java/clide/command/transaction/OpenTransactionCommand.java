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
 * Opens a transaction (or sub-transaction) - see TransactionStack, CLAUDE.md.
 */
public class OpenTransactionCommand extends Command {

	@Keyword("open_transaction")
	@Help("Opens <transaction id> so file modifications made under it can later be committed or rolled back.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Manual("""
			NAME
				open_transaction - open a transaction

			SYNOPSIS
				open_transaction <transaction id>

			DESCRIPTION
				Opens <transaction id>, an identifier starting with "$"
				followed by lower-case word characters (e.g. "$refactor_foo").
				No file-modifying command is allowed to run until at least
				one transaction is open - see Command.needsOpenTransaction().

				A transaction id can be chained to open a sub-transaction of
				the one currently open, e.g. once "$refactor_foo" is open,
				"$refactor_foo$part1" can be opened next, then
				"$refactor_foo$part1$a" under that. Only the transaction
				currently on top can be extended this way - a second,
				unrelated sub-transaction of "$refactor_foo" cannot be
				opened until "$refactor_foo$part1" is first committed or
				rolled back.

			ERRORS
				Refused if <transaction id> does not extend the id currently
				open (or, if none is open, is not a single segment), or if
				it is already open.

			SEE ALSO
				commit_transaction(1), rollback_transaction(1), diff_transaction(1), restore_file(1)
			""")
	public OpenTransactionCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		try {
			context.getTransactions().open(id);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}
		return CommandResult.ok(new CommandPayload.Transaction(id, CommandPayload.Transaction.Action.OPENED, ""));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return TransactionRendering.render(result);
	}

}
