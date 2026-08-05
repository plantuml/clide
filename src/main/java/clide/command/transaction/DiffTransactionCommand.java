package clide.command.transaction;

import java.io.IOException;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.result.CommandPayload;
import clide.command.result.CommandResult;
import clide.command.result.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.TransactionStack;
import clide.result.Listing;
import clide.util.UnifiedDiff;

/**
 * Reports what a transaction modified - see TransactionStack, CLAUDE.md.
 */
public class DiffTransactionCommand extends Command {

	@Keyword("diff_transaction")
	@Help("Lists the files <transaction id> modified, or - if <file path> is given - a unified diff of just that file.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Param(type = ParamType.SINGLE_LINE, description = "File path (empty lists every modified file instead)")
	@Manual("""
			NAME
				diff_transaction - show what a transaction modified

			SYNOPSIS
				diff_transaction <transaction id>
				diff_transaction <transaction id> <file path>

			DESCRIPTION
				With <file path> left empty, lists every file modified
				under <transaction id> - its own changes plus those of any
				still-open sub-transaction of it, one relative path per
				line.

				With <file path> given, shows a unified diff ("---"/"+++"/
				"@@" hunks, same format as `diff -u`) between the state
				<file path> had right before <transaction id>'s subtree
				first touched it, and its current, live content on disk.

			ERRORS
				Refused if <transaction id> is not currently open, or if
				<file path> is given but was not modified under it.

			SEE ALSO
				open_transaction(1), restore_file(1)
			""")
	public DiffTransactionCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		final String filePath = params[1];
		final TransactionStack transactions = context.getTransactions();

		try {
			if (filePath.isEmpty())
				return listModifiedFiles(context, transactions, id);

			return diffOneFile(transactions, id, filePath);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}
	}

	private CommandResult listModifiedFiles(final ClideContext context, final TransactionStack transactions,
			final String id) throws IOException {
		return CommandResult.ok(new CommandPayload.ModifiedFiles(id,
				Listing.of(transactions.modifiedFiles(id), context.getMaxResults())));
	}

	private CommandResult diffOneFile(final TransactionStack transactions, final String id, final String filePath)
			throws IOException {
		final List<String> before = transactions.beforeLines(id, filePath);
		final List<String> current = transactions.currentLines(filePath);
		return CommandResult.ok(new CommandPayload.Diff(id, filePath,
				UnifiedDiff.render(before, current, "a/" + filePath, "b/" + filePath)));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.ModifiedFiles listed) {
			final Listing<String> files = listed.files();
			if (files.totalCount() == 0)
				return "Transaction " + listed.transactionId() + " has not modified any file yet.";

			final StringBuilder out = new StringBuilder();
			out.append("diff_transaction: ").append(files.summarize("file"));
			for (final String file : files.items())
				out.append('\n').append(file);

			return out.toString();
		}

		if (result.payload() instanceof CommandPayload.Diff diff) {
			if (diff.unifiedDiff().isEmpty())
				return "No differences (current content matches the pre-transaction backup).";

			return diff.unifiedDiff();
		}

		return "";
	}

}
