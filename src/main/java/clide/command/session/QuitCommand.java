package clide.command.session;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;

/** Stops the jdtls session but keeps the clide daemon running. Synonym: exit. */
public class QuitCommand extends DisconnectCommand {

	@Keyword("quit")
	@Help("Stops the jdtls session but keeps the clide daemon running - see terminate to shut it down entirely.")
	@Manual("""
			NAME
				quit - stop the jdtls session, keep the daemon running

			SYNOPSIS
				quit

			DESCRIPTION
				Exact synonym of exit - identical behavior, only the keyword
				differs. Stops the project's jdtls session but leaves the
				clide daemon (and its .clide.lock) running, ready to serve
				the next connection; the next command that actually needs
				jdtls restarts the session for you first, automatically, and
				any transaction still open stays open, untouched, for that
				next connection to resume - quit prints a warning first if
				that's the case, but never blocks on it. See terminate
				instead to shut the daemon itself down, not just the session
				- unlike exit/quit, terminate refuses to run while any
				transaction is still open.

			SEE ALSO
				exit(1), terminate(1), open_transaction(1)
			""")
	public QuitCommand() {

	}

}
