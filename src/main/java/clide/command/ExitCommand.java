package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;

/** Stops the jdtls session but keeps the clide daemon running. Synonym: quit. */
public class ExitCommand extends DisconnectCommand {

	@Keyword("exit")
	@Help("Stops the jdtls session but keeps the clide daemon running - see terminate to shut it down entirely.")
	public ExitCommand() {
		
	}

}
