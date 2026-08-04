package clide.result;

/**
 * Something worth saying about a result that is otherwise fine. Carried as a
 * (possibly empty) list on every CommandResult, OK ones included - see
 * CommandStatus for why this is a field rather than a third status value.
 */
public record Warning(WarningCode code, String message) {

	public Warning {
		if (code == null)
			throw new IllegalArgumentException("code must not be null");

		if (message == null || message.isEmpty())
			throw new IllegalArgumentException("a warning must say something - empty message for " + code);
	}

	public static Warning of(final WarningCode code, final String message) {
		return new Warning(code, message);
	}

}
