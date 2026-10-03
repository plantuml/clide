package clide.command;

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

/**
 * Adds an environment variable to the JVM run_test and run_tests fork.
 *
 * A test that reads System.getenv() cannot be steered any other way: the fork is
 * a child of the daemon, so it inherits the daemon's environment and nothing a
 * client can say. This is the same kind of setting as set_max_results - per
 * connection, reported with its previous value because the protocol has no
 * argument-less form to read it back with - and it goes back to nothing at the
 * start of every connection for the same reason.
 */
public class SetTestEnvCommand extends Command {

	/** What the "previous value" of a variable this connection had not set reads as. */
	static final String UNSET = "(unset)";

	@Keyword("set_test_env")
	@Help("Sets an environment variable for the JVM run_test/run_tests start, for this session only - <name> then <value>.")
	@Param(type = ParamType.SINGLE_LINE, description = "Name")
	@Param(type = ParamType.SINGLE_LINE, description = "Value")
	@Manual("""
			NAME
				set_test_env - set an environment variable for the test JVM

			SYNOPSIS
				set_test_env <name> <value>

			DESCRIPTION
				Adds <name>=<value> to the environment of the JVM that
				run_test and run_tests start, on top of the daemon's own.
				A value set again replaces the earlier one. Prints the
				previous value, or "(unset)" if this session had not set the
				variable - which is also the only way to read a setting back,
				the protocol's fixed arity leaving no argument-less form.

				The setting belongs to the connection, not to the daemon: it
				is dropped at the start of every new session, and
				reset_test_settings drops it earlier. A variable that changes
				what a test does (one that makes it rewrite reference files,
				say) must never outlive the session that asked for it.

				It only reaches the test JVM. Nothing else clide starts, jdtls
				included, sees it, and it cannot remove a variable the daemon
				already has - only override it.

			ERRORS
				<name> must be letters, digits and underscores, and must not
				start with a digit; anything else is refused. <value> is taken
				verbatim, one line, and may be empty only if the client can
				send an empty line.

			SEE ALSO
				set_test_classpath_prefix(1), set_test_jvm_options(1), reset_test_settings(1), run_test(1)
			""")
	public SetTestEnvCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final String previous;
		try {
			previous = context.setTestEnvironment(params[0].strip(), params[1]);
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, e.getMessage());
		}

		return CommandResult.ok(new CommandPayload.Setting("test_env." + params[0].strip(),
				previous == null ? UNSET : previous, params[1]));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Setting setting ->
			"set_test_env: " + setting.name() + " " + setting.previousValue() + " -> " + setting.newValue();
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
