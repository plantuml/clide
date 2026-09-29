package clide.command;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Forgets what set_test_env and set_test_classpath_prefix set on this
 * connection, so a script can run the same tests twice under different
 * conditions without the first run's settings leaking into the second.
 */
public class ResetTestSettingsCommand extends Command {

	@Keyword("reset_test_settings")
	@Help("Removes every set_test_env and set_test_classpath_prefix of this session.")
	@Manual("""
			NAME
				reset_test_settings - drop the test environment and classpath prefix

			SYNOPSIS
				reset_test_settings

			DESCRIPTION
				Removes every variable set with set_test_env and the prefix set
				with set_test_classpath_prefix, so the next run_test or
				run_tests starts the project's own classes with the daemon's own
				environment. Never fails, and does nothing if nothing was set.
				A new session starts in that state anyway; this is for going
				back within one.

			SEE ALSO
				set_test_env(1), set_test_classpath_prefix(1)
			""")
	public ResetTestSettingsCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		context.resetTestSettings();
		return CommandResult.empty();
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Nothing nothing -> "reset_test_settings: done";
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
