package clide.daemon;

import clide.PrintMode;

/**
 * What a client announced itself as on its very first line, and therefore how
 * the daemon serves it: a stream of commands to read one by one (AI), the same
 * with prompts for someone typing (HUMAN), or a single Lua script to read whole
 * and run (SCRIPT).
 *
 * Distinct from PrintMode on purpose. PrintMode says how a result is *written*
 * for whoever reads it, and both AI and HUMAN are answers to that question. A
 * script connection is not: it renders no result at all - the Lua bridge hands
 * payloads to the script as tables, and the only text on the wire is whatever
 * the script itself prints. Folding SCRIPT into PrintMode would have made every
 * "if (printMode == HUMAN)" in the daemon quietly mean "and not a script
 * either".
 *
 * of() reads the first line and nothing else. A line that is neither flag is
 * not a handshake at all but already this session's first command - which is
 * what keeps a bare socket session, netcat included, working with no preamble:
 * no command keyword can look like "--human" or "--lua", so no command is ever
 * mistaken for a handshake.
 */
public enum ConnectionMode {

	AI, HUMAN, SCRIPT;

	/**
	 * Both the command-line flag that selects SCRIPT mode and the handshake line
	 * announcing it - the same string, exactly as PrintMode.HUMAN_FLAG is, and
	 * deliberately not a valid command keyword.
	 */
	public static final String SCRIPT_FLAG = "--lua";

	public static ConnectionMode of(final String firstLine) {
		final String announced = firstLine.trim();
		if (announced.equals(PrintMode.HUMAN_FLAG))
			return HUMAN;

		if (announced.equals(SCRIPT_FLAG))
			return SCRIPT;

		return AI;
	}

	/**
	 * How results are written under this mode. SCRIPT maps to AI: a script
	 * connection prints no prompt, and the handful of commands whose output
	 * depends on the mode (help) have no reason to read differently for it.
	 */
	public PrintMode printMode() {
		return this == HUMAN ? PrintMode.HUMAN : PrintMode.AI;
	}

	/** Whether this mode consumed the first line as its handshake. */
	public boolean announced() {
		return this != AI;
	}

}
