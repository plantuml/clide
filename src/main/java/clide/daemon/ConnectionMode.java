package clide.daemon;

/**
 * What a client announced itself as on its very first line, and therefore how
 * the daemon serves it: a single Lua script, read whole and run (SCRIPT), or a
 * stream of commands, read one by one (COMMANDS) - the ordinary case.
 *
 * Distinct from PrintMode on purpose, and for a narrower reason than it used
 * to be. PrintMode says how a result is *written*, and used to be a second
 * thing a connection could announce here (HUMAN); it no longer is - the print
 * mode is now fixed for the whole daemon at startup (see Main, ClideDaemon),
 * so a connection has nothing left to say about it. What is still a property
 * of one connection is whether it carries a script: a SCRIPT connection
 * renders no result at all - the Lua bridge hands payloads to the script as
 * tables, and the only text on the wire is whatever the script itself prints
 * - which is why it stays its own mode here rather than folding into
 * PrintMode.
 *
 * of() reads the first line and nothing else. A line that is not the script
 * flag is not a handshake at all but already this session's first command -
 * which is what keeps a bare socket session, netcat included, working with no
 * preamble: no command keyword can look like "--lua", so no command is ever
 * mistaken for a handshake.
 */
public enum ConnectionMode {

	COMMANDS, SCRIPT;

	/**
	 * Both the command-line flag clide.py uses to select this mode and the
	 * handshake line announcing it - the same string, and deliberately not a
	 * valid command keyword.
	 */
	public static final String SCRIPT_FLAG = "--lua";

	public static ConnectionMode of(final String firstLine) {
		return firstLine.trim().equals(SCRIPT_FLAG) ? SCRIPT : COMMANDS;
	}

	/** Whether this mode consumed the first line as its handshake. */
	public boolean announced() {
		return this == SCRIPT;
	}

}
