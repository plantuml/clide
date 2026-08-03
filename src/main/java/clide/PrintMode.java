package clide;

/**
 * How a session talks back to whoever is on the other end of the socket. AI -
 * the default - prints nothing but the commands' own output: no "&gt; READY"
 * between commands, no "&gt; &lt;parameter&gt; ?" while a command's parameters are
 * being read. HUMAN prints both, so someone typing by hand can see what is
 * expected next instead of having to know each command's arity by heart.
 *
 * The mode belongs to one connection, not to the daemon: the client picks it
 * ("clide --human &lt;project&gt;") and announces it to the daemon as a handshake
 * line at the start of that connection - see ClideClient.announcePrintMode()
 * and ClideDaemon.runSession(). Two clients can therefore talk to the same
 * daemon in different modes, and nothing a human does leaks into the next AI
 * session. An AI session's byte stream is also exactly what it was before this
 * flag existed: the default mode announces nothing at all.
 */
public enum PrintMode {
	HUMAN, AI;

	/**
	 * Both the command-line flag that selects HUMAN mode and the handshake line
	 * announcing it - deliberately the same string, and deliberately not a valid
	 * command keyword, so a daemon reading it as the first line of a connection
	 * can tell it apart from a command with no ambiguity.
	 */
	public static final String HUMAN_FLAG = "--human";
}
