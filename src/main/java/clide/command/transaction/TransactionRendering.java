package clide.command.transaction;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;

/**
 * The one sentence open/commit/rollback/restore_file each answer with. Shared so
 * the four read alike, and so adding a fifth action means adding a case here
 * rather than inventing a fifth phrasing.
 */
final class TransactionRendering {

	private TransactionRendering() {
	}

	static String render(final CommandResult result) {
		if (result.payload() instanceof CommandPayload.Transaction transaction) {
			return switch (transaction.action()) {
			case OPENED -> "Transaction " + transaction.id() + " opened.";
			case COMMITTED -> "Transaction " + transaction.id() + " committed.";
			case ROLLED_BACK -> "Transaction " + transaction.id() + " rolled back.";
			case FILE_RESTORED -> "Restored " + transaction.path() + " to its state before transaction "
					+ transaction.id() + ".";
			};
		}

		return "";
	}

}
