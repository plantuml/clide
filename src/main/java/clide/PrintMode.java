package clide;

/**
 * How a session talks back to whoever is on the other end of the socket. AI -
 * the default - prints nothing but the commands' own output: no "&gt; READY"
 * between commands, no "&gt; &lt;parameter&gt; ?" while a command's parameters are
 * being read. HUMAN prints both, so someone typing by hand can see what is
 * expected next instead of having to know each command's arity by heart.
 *
 * The mode belongs to the daemon, not to one connection: it is chosen once,
 * when the daemon starts ("java -jar clide.jar --human &lt;project&gt;" - see
 * Main), and every client that connects afterward - clide.py relaying a
 * keyboard, or relaying a --lua script - is served in that one mode for as
 * long as the daemon stays up. It cannot be changed without restarting the
 * daemon. (An earlier, Java-client architecture let each connection pick its
 * own mode independently of any other connection to the same daemon; see
 * HISTORY.md for that previous design.)
 */
public enum PrintMode {
	HUMAN, AI;

	/** The command-line flag that selects HUMAN mode when starting the daemon - see Main. */
	public static final String HUMAN_FLAG = "--human";
}
