package clide.core;

/**
 * Outcome of a single command execution: a status plus the text to print (never
 * null, may be empty when there is nothing to say).
 */
public record CommandResult(CommandStatus status, String message) {

	public CommandResult {
		if (message == null)
			throw new IllegalArgumentException("message must not be null");
	}

	public static CommandResult ok(final String message) {
		return new CommandResult(CommandStatus.OK, message);
	}

	public static CommandResult error(final String message) {
		return new CommandResult(CommandStatus.ERROR, message);
	}

}
