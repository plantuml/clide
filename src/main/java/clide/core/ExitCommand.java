package clide.core;

import clide.annotation.Help;
import clide.annotation.Keyword;

/** Stops every open jdtls session and tells the shell to stop reading input. */
public class ExitCommand extends Command {

	@Keyword("exit")
	@Help("Stops every open jdtls session and quits clide.")
	public ExitCommand() {
		// Constructeur
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		context.stopAllSessions();
		context.requestExit();
		return CommandResult.ok("");
	}

}
