package clide.command.transaction;

import java.io.IOException;
import java.util.List;

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
import clide.core.TransactionStack;
import clide.util.UnifiedDiff;

/**
 * Reports a unified diff of one file a transaction modified - see
 * TransactionStack, CLAUDE.md.
 *
 * Used to also answer "what changed" with no <file path> given (a second,
 * differently-shaped CommandPayload - see git history around 2026-08). Split
 * off into ListModifiedFilesCommand instead: one command, one payload shape
 * is the invariant a future Lua binding needs (see LUA.md, "Génération des
 * fonctions Lua") - a reflection-generated Lua function assumes the command it
 * wraps always answers the same shape, which this command alone could not
 * promise as long as its answer depended on whether <file path> was empty.
 */
public class DiffTransactionCommand extends Command {

	@Keyword("diff_transaction")
	@Help("Shows a unified diff of <file path> as modified under <transaction id>, against its state right before the transaction touched it.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Param(type = ParamType.SINGLE_LINE, description = "File path")
	@Manual("""
			NAME
				diff_transaction - show what a transaction did to one file

			SYNOPSIS
				diff_transaction <transaction id> <file path>

			DESCRIPTION
				Shows a unified diff ("---"/"+++"/"@@" hunks, same format as
				`diff -u`) between the state <file path> had right before
				<transaction id>'s subtree first touched it, and its current,
				live content on disk.

				Use list_modified_files instead to see which files a
				transaction touched, without diffing any of them.

			ERRORS
				Refused if <transaction id> is not currently open, or if
				<file path> was not modified under it.

			SEE ALSO
				list_modified_files(1), open_transaction(1), restore_file(1)
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
		if (filePath.isEmpty())
			return CommandResult.error(ErrorCode.EMPTY_PARAMETER,
					"diff_transaction needs a <file path> - use list_modified_files to see which files changed");

		final TransactionStack transactions = context.getTransactions();

		try {
			final List<String> before = transactions.beforeLines(id, filePath);
			final List<String> current = transactions.currentLines(filePath);
			return CommandResult.ok(new CommandPayload.Diff(id, filePath,
					UnifiedDiff.render(before, current, "a/" + filePath, "b/" + filePath)));
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Diff diff -> diff.unifiedDiff().isEmpty()
				? "No differences (current content matches the pre-transaction backup)."
				: diff.unifiedDiff();
		default -> "";
		};
	}

}
