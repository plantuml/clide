package clide.command;

import java.io.IOException;
import java.util.List;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.core.TransactionStack;
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
				return listModifiedFiles(transactions, id);

			return diffOneFile(transactions, id, filePath);
		} catch (final IOException | IllegalArgumentException e) {
			return CommandResult.error(e.getMessage());
		}
	}

	private CommandResult listModifiedFiles(final TransactionStack transactions, final String id)
			throws IOException {
		final List<String> files = transactions.modifiedFiles(id);
		if (files.isEmpty())
			return CommandResult.ok("Transaction " + id + " has not modified any file yet.");

		return CommandResult.ok(String.join("\n", files));
	}

	private CommandResult diffOneFile(final TransactionStack transactions, final String id, final String filePath)
			throws IOException {
		final List<String> before = transactions.beforeLines(id, filePath);
		final List<String> current = transactions.currentLines(filePath);
		final String diff = UnifiedDiff.render(before, current, "a/" + filePath, "b/" + filePath);
		if (diff.isEmpty())
			return CommandResult.ok("No differences (current content matches the pre-transaction backup).");

		return CommandResult.ok(diff);
	}

}
