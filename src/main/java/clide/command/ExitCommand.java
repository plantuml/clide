package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;

/** Stops the jdtls session but keeps the clide daemon running. Synonym: quit. */
public class ExitCommand extends DisconnectCommand {

	@Keyword("exit")
	@Help("Stops the jdtls session but keeps the clide daemon running - see terminate to shut it down entirely.")
	@Manual("""
			NAME
				exit - stop the jdtls session, keep the daemon running

			SYNOPSIS
				exit

			DESCRIPTION
				Stops the project's jdtls session but leaves the clide daemon
				(and its .clide.lock) running, ready to serve the next
				connection. Nothing is lost for good: the next command that
				actually needs jdtls restarts the session for you first,
				automatically. Exact synonym of quit - the two differ only in
				keyword, never in behavior. See terminate instead to shut the
				daemon itself down, not just the session.

			SEE ALSO
				quit(1), terminate(1)
			""")
	public ExitCommand() {
		
	}

}
