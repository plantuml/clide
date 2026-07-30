package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

/** Stops the jdtls session and shuts down the clide daemon entirely. */
public class TerminateCommand extends Command {

	@Keyword("terminate")
	@Help("Stops the jdtls session and shuts down the clide daemon.")
	@Manual("""
			NAME
				terminate - stop the jdtls session and shut down the daemon

			SYNOPSIS
				terminate

			DESCRIPTION
				Stops the project's jdtls session, the same first step as
				exit/quit, then goes further and shuts the clide daemon
				itself down, releasing .clide.lock. The next "clide <project
				path>" run for this project starts a fresh daemon - and, in
				turn, a fresh jdtls session - rather than reconnecting to
				this one. Use exit or quit instead when only the session,
				not the daemon, should be freed.

			SEE ALSO
				exit(1), quit(1)
			""")
	public TerminateCommand() {
		
	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		context.stopSession();
		context.requestShutdown();
		return CommandResult.ok("");
	}

}
