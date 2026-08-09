package clide.core;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import clide.annotation.ParamType;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.jdtls.JdtlsSession;

/**
 * The one place a command actually runs, whichever façade asked for it: the
 * line-oriented text protocol (see ClideDaemon.runSession()) and the Lua bridge
 * (see LuaBridge) both come through here.
 *
 * <b>Why this is not just a call to Command.executeCommand().</b> Four checks
 * stand between a client's request and a command running, and none of them
 * lives inside the command: every parameter has to pass its ParamType's surface
 * check, a command that modifies files has to find a transaction open, a
 * command that queries jdtls has to find a session up (or get one restarted),
 * and jdtls has to have been told about whatever moved on disk since it last
 * looked (see ModelSync).
 * Left in the daemon's read loop, as they were, they would have guarded the
 * text protocol only - a Lua script calling executeCommand() directly could
 * have edited a file outside any transaction, or passed a position nobody ever
 * checked against the file it names. Two façades, one gate: the guards cannot
 * drift apart because there is only one copy of them.
 *
 * What is deliberately NOT here: reading the parameters. The "one token per
 * line" codec, its MULTI_LINE terminator and its MISSING_PARAMETERS case belong
 * to the text protocol alone (see ClideDaemon.readParams()) - Lua hands over
 * its arguments already separated. By the time dispatch() is called, params is
 * complete and command.paramSize() long, whoever built it.
 *
 * notice is where the few things that are neither the result nor an error go -
 * today only the "restarting jdtls" line, printed while a client waits. The
 * text protocol points it at the client's socket; a script points it at the
 * same stream its print() writes to. It is never the way a result travels back:
 * that is the returned CommandResult, and only that.
 */
public final class CommandDispatcher {

	private CommandDispatcher() {
	}

	/**
	 * Validates params, checks what command declares it needs, and runs it. Returns
	 * the command's own CommandResult, or the error that stopped it from ever
	 * running - a caller cannot tell the two apart, and does not have to: both are
	 * answers to send back.
	 */
	public static CommandResult dispatch(final ClideContext context, final Command command,
			final Consumer<String> notice, final String... params) {
		final CommandResult invalid = validateParams(context.getFilesRepository(), command, params);
		if (invalid != null)
			return invalid;

		if (command.needsOpenTransaction() && context.getTransactions().hasAnyOpen() == false)
			return CommandResult.error(ErrorCode.NO_OPEN_TRANSACTION,
					command.getKeyword() + " requires an open transaction - see open_transaction");

		if (command.needsJdtlsSession()) {
			final CommandResult restartFailure = ensureSessionReady(context, notice);
			if (restartFailure != null)
				return restartFailure;

			// After ensureSessionReady(), never before: restarting a stopped session
			// runs a build of its own, so there is nothing left to resynchronise and
			// doing it first would pay a full-project scan for nothing.
			final CommandResult stale = ModelSync.beforeCommand(context, command);
			if (stale != null)
				return stale;
		}

		return command.executeCommand(context, params);
	}

	/**
	 * Lazily restarts the jdtls session if a previous "exit"/"quit" stopped it
	 * while leaving the daemon running. Only reached for commands that declare
	 * needsJdtlsSession() - a cheap no-op (one boolean read) when the session is
	 * already up. Returns an error CommandResult if the restart itself fails, null
	 * once the session is ready to use.
	 */
	private static CommandResult ensureSessionReady(final ClideContext context, final Consumer<String> notice) {
		final JdtlsSession session = context.getCurrentSession();
		if (session.isReady())
			return null;

		notice.accept("jdtls session was stopped (exit/quit) - restarting it ...");
		try {
			// Same try/finally shape as the daemon's initial start+build, and for the
			// same reason: restoreEclipseFiles() must run whichever of the two throws,
			// or not, so a re-staged .project/.classpath never outlives this restart.
			try {
				session.start();
				session.build();
			} finally {
				session.restoreEclipseFiles();
			}
			return null;
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.SESSION_START_FAILED,
					"Failed to restart jdtls session: " + e.getMessage());
		}
	}

	/**
	 * Runs validate() over every parameter, in order, before the command they
	 * belong to ever executes. Returns the first error found, or null once every
	 * parameter has passed.
	 */
	private static CommandResult validateParams(final FilesRepository filesRepository, final Command command,
			final String[] params) {
		final ParamType[] types = command.getParamTypes();
		for (int i = 0; i < params.length; i++) {
			final CommandResult error = validate(filesRepository, types[i], params[i]);
			if (error != null)
				return error;
		}
		return null;
	}

	/**
	 * Surface-level check for one parameter's raw text, run purely on that text -
	 * TRANSACTION_ID must match TransactionStack.ID_PATTERN, REGEX must compile
	 * (java.util.regex.Pattern), POSITION must parse as a real
	 * file/line/column/word (see PositionParser.parse()). Every other ParamType has
	 * nothing to check here. Returns null when value is acceptable, or an error fit
	 * to send back to the client as-is otherwise.
	 */
	private static CommandResult validate(final FilesRepository filesRepository, final ParamType type,
			final String value) {
		switch (type) {
		case TRANSACTION_ID:
			if (TransactionStack.ID_PATTERN.matcher(value).matches() == false)
				return CommandResult.error(ErrorCode.INVALID_TRANSACTION_ID,
						"Invalid transaction id '" + value + "' - expected $segment, lowercase word characters only "
								+ "(e.g. $refactor_foo, $refactor_foo$part1)");
			return null;
		case REGEX:
			try {
				Pattern.compile(value);
			} catch (final PatternSyntaxException e) {
				return CommandResult.error(ErrorCode.INVALID_REGEX, "Invalid regex '" + value + "': " + e.getMessage());
			}
			return null;
		case POSITION:
			try {
				PositionParser.parse(filesRepository, value);
			} catch (final IllegalArgumentException e) {
				// PositionException carries which of the ways it failed, and the hint
				// that goes with it when there is one (NAME_NOT_AT_COLUMN names the
				// columns the name really starts at) - both have to travel from here,
				// since this surface check runs before the command itself and is what
				// the client actually sees. Anything else would be a bug in Position,
				// reported rather than swallowed.
				return CommandResult.error(PositionException.codeOf(e), e.getMessage(), PositionException.hintOf(e));
			}
			return null;
		case NON_NEGATIVE_INTEGER:
			return validateNonNegativeInteger(value);
		default:
			return null;
		}
	}

	/**
	 * Zero is accepted and means zero; a negative or unparsable value is refused
	 * naming the parameter rather than repaired into something plausible. Any upper
	 * bound belongs to the command, not to the type - see SetMaxResultsCommand.
	 */
	private static CommandResult validateNonNegativeInteger(final String value) {
		final int parsed;
		try {
			parsed = Integer.parseInt(value.strip());
		} catch (final NumberFormatException e) {
			return CommandResult.error(ErrorCode.INVALID_INTEGER,
					"Invalid count '" + value + "' - expected an integer of 0 or more");
		}

		if (parsed < 0)
			return CommandResult.error(ErrorCode.INVALID_INTEGER,
					"Invalid count '" + value + "' - expected an integer of 0 or more, not a negative one");

		return null;
	}

}
