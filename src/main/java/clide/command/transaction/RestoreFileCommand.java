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
import clide.core.ModelSync;
import clide.core.Command;

/**
 * Restores a single file to its pre-transaction state - see TransactionStack, CLAUDE.md.
 */
public class RestoreFileCommand extends Command {

	@Keyword("restore_file")
	@Help("Restores <file path> to the state it had before <transaction id> first modified it.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Param(type = ParamType.SINGLE_LINE, description = "File path")
	@Manual("""
			NAME
				restore_file - restore a single file to its pre-transaction state

			SYNOPSIS
				restore_file <transaction id> <file path>

			DESCRIPTION
				Restores <file path> to the state it had right before
				<transaction id> was opened - deleted outright if it did
				not exist yet at that point.

				Unlike rollback_transaction, this only touches <file path>
				- every other file <transaction id> modified is left as-is,
				and the transaction itself stays open. The pre-transaction
				state used is not discarded, so diff_transaction and
				restore_file keep working normally afterwards if called
				again.

			ERRORS
				Refused if <transaction id> is not currently open, or if
				<file path> was not modified under it.

			SEE ALSO
				diff_transaction(1), rollback_transaction(1)
			""")
	public RestoreFileCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		final String filePath = params[1];
		try {
			context.getTransactions().restoreFile(id, filePath);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}

		// Same as rollback_transaction: a file was just put back to earlier content
		// that jdtls has no other way of hearing about - see ModelSync.afterRestore().
		ModelSync.afterRestore(context);

		return CommandResult.ok(
				new CommandPayload.Transaction(id, CommandPayload.Transaction.Action.FILE_RESTORED, filePath));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return TransactionRendering.render(getKeyword(), result);
	}

}
