package clide.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
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
 * and its answer has to be about the project as it stands rather than as the
 * last build left it (see rejectIfModelIsStale()).
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

	/** How many changed files a STALE_MODEL message names before it stops listing them. */
	private static final int STALE_FILES_NAMED = 10;

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
			// runs a build of its own, which is exactly what makes the model fresh
			// again. Checking first would refuse a command that restart was about to
			// make perfectly answerable.
			final CommandResult stale = rejectIfModelIsStale(context, command);
			if (stale != null)
				return stale;
		}

		return command.executeCommand(context, params);
	}

	/**
	 * Refuses a command whose answer would describe the project as the last
	 * build left it rather than as it now stands - see Command.needsFreshModel()
	 * and ErrorCode.STALE_MODEL.
	 *
	 * Here rather than in each command, for the reason needsOpenTransaction() is
	 * here: it is a precondition on running at all, not part of what any one
	 * command does, and a precondition copied into a dozen classes is one that a
	 * thirteenth will eventually be written without. It also puts the check on
	 * the single path both façades take, so a Lua script cannot reach past it.
	 *
	 * <b>What it costs.</b> One full-project file scan per command - the very
	 * scan a rebuild already pays: an md5 over every .java file, read in
	 * parallel (see FilesRepository.currentSourceFiles()). Measured on the
	 * PlantUML checkout, 3633 sources, cache-warm, 2 cores: about 180 ms. What
	 * it buys is that every find_*, hover and list_members answers about this
	 * project rather than possibly about a former one, which is the whole reason
	 * clide is preferred to a grep.
	 *
	 * Cheaper is possible and deliberately not done yet. On that same checkout
	 * the walk alone costs ~40 ms and stat-ing every file for its mtime and size
	 * ~12 ms, so a "nothing looks touched" pre-check would answer the common
	 * case in a third of the time. It is not free of meaning, though: it would
	 * trade Snapshot's content-based definition of "changed" (see its class doc)
	 * for a timestamp one, and miss an edit that preserved both mtime and size.
	 * Worth doing on measured need, not on principle.
	 */
	private static CommandResult rejectIfModelIsStale(final ClideContext context, final Command command) {
		if (command.needsFreshModel() == false)
			return null;

		final Delta delta;
		try {
			delta = context.getCurrentSession().changesSinceLastBuild();
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED,
					"could not check whether the project changed since the last build: " + e.getMessage());
		}

		if (delta.isEmpty())
			return null;

		return CommandResult.error(ErrorCode.STALE_MODEL, staleMessage(context.getProjectRoot(), delta, command));
	}

	private static String staleMessage(final Path projectRoot, final Delta delta, final Command command) {
		final StringBuilder message = new StringBuilder();
		message.append(delta.size()).append(" .java file(s) changed since the last build, so ")
				.append(command.getKeyword()).append(" would answer about another state of this project")
				.append(" - run rebuild first:");

		int named = 0;
		for (final FileChange change : delta.changes()) {
			if (named == STALE_FILES_NAMED) {
				message.append("\n... and ").append(delta.size() - named).append(" more");
				break;
			}
			message.append('\n').append(change.type().name().toLowerCase(Locale.ROOT)).append(' ')
					.append(relativize(projectRoot, change.path()));
			named++;
		}

		return message.toString();
	}

	/**
	 * A FileChange carries the absolute path a Snapshot walked; nothing clide
	 * prints ever may (see TODO.md). Falls back to the path as given if it does
	 * not sit under the project, rather than throwing while building an error
	 * message - a wrong path in a diagnostic is a nuisance, an exception raised
	 * while reporting a problem hides the problem.
	 */
	private static String relativize(final Path projectRoot, final String absolute) {
		try {
			return projectRoot.relativize(Path.of(absolute)).toString().replace('\\', '/');
		} catch (final RuntimeException e) {
			return absolute;
		}
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
