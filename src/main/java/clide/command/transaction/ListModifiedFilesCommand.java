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
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.TransactionStack;
import clide.model.Listing;

/**
 * Lists the files a transaction has modified so far - see TransactionStack,
 * CLAUDE.md.
 *
 * Split off from what used to be diff_transaction's own "no &lt;file path&gt;"
 * mode (see git history around 2026-08 - the two questions "what changed" and
 * "show me the diff of this one file" answered with two different
 * CommandPayload shapes under one command name). One command, one payload
 * shape is the invariant a future Lua binding needs (see LUA.md, "Génération
 * des fonctions Lua"): a reflection-generated Lua function assumes the
 * command it wraps always answers the same shape, which diff_transaction
 * alone could not promise as long as its answer depended on whether
 * &lt;file path&gt; was empty.
 */
public class ListModifiedFilesCommand extends Command {

	@Keyword("list_modified_files")
	@Help("Lists the files <transaction id> has modified so far, one relative path per line.")
	@Param(type = ParamType.TRANSACTION_ID, description = "Transaction id")
	@Manual("""
			NAME
				list_modified_files - list the files a transaction has modified

			SYNOPSIS
				list_modified_files <transaction id>

			DESCRIPTION
				Lists every file modified under <transaction id> - its own
				changes plus those of any still-open sub-transaction of it,
				one relative path per line.

			ERRORS
				Refused if <transaction id> is not currently open.

			SEE ALSO
				open_transaction(1), diff_transaction(1), restore_file(1)
			""")
	public ListModifiedFilesCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String id = params[0];
		final TransactionStack transactions = context.getTransactions();

		try {
			return CommandResult.ok(new CommandPayload.ModifiedFiles(id,
					Listing.of(transactions.modifiedFiles(id), context.getMaxResults())));
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_IO_FAILED, e.getMessage());
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.TRANSACTION_REFUSED, e.getMessage());
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.ModifiedFiles listed -> {
			final Listing<String> files = listed.files();
			if (files.totalCount() == 0)
				yield "Transaction " + listed.transactionId() + " has not modified any file yet.";

			final StringBuilder out = new StringBuilder();
			out.append("list_modified_files: ").append(files.summarize("file"));
			for (final String file : files.items())
				out.append('\n').append(file);

			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
