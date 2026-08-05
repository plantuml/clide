package clide.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import clide.PrintMode;
import clide.jdtls.JdtlsSession;

/**
 * State shared across every command execution for the lifetime of the clide
 * daemon: the project's root (what a ParamType.POSITION notation's relative
 * file path resolves against - see PositionParser.parse() - never the daemon process' own
 * current directory), the single jdtls session for this project, the list of
 * registered commands (for help), and the two distinct ways a client
 * interaction can end:
 * <ul>
 * <li>"exit"/"quit" (see DisconnectCommand) - stop the jdtls session and end
 * only the current connection. The daemon and its .clide.lock stay up; the next
 * command that actually needs jdtls restarts it lazily - see
 * ClideDaemon.ensureSessionReady().</li>
 * <li>"terminate" (see TerminateCommand) - stop the jdtls session, end the
 * connection, and shut the whole daemon down.</li>
 * </ul>
 * ClideDaemon resets isDisconnectRequested() at the start of every new
 * connection; isShutdownRequested() is one-way and checked by both the
 * per-connection loop and the daemon's own accept loop.
 *
 * getPrintMode() is per-connection too, in the same sense: ClideDaemon sets it
 * from the handshake the moment a connection announces its mode, and it stays
 * that connection's mode until the next one overwrites it. Sharing one field
 * for that is safe because the daemon serves clients strictly one at a time
 * (see ClideDaemon's class doc) - never two connections at once.
 */
public class ClideContext {

	private final Map<String, Command> commandsByKeywords = new TreeMap<>();

	/**
	 * How many entries a listing command returns unless this connection says
	 * otherwise. High enough that a normal question is answered in full, low
	 * enough that find_reference on something like PlantUML's UGraphic does not
	 * bury the answer under its own output.
	 */
	public static final int DEFAULT_MAX_RESULTS = 100;

	/**
	 * The largest value set_max_results accepts. Not a silent clamp: a request
	 * above it is refused, naming the ceiling, because a cap that quietly ignores
	 * what it was told is how a client ends up believing it disabled truncation.
	 */
	public static final int MAX_RESULTS_CEILING = 10000;

	private final Path projectRoot;
	private final JdtlsSession session;
	private final TransactionStack transactions;
	private boolean shutdownRequested;
	private boolean disconnectRequested;
	private PrintMode printMode = PrintMode.AI;
	private int maxResults = DEFAULT_MAX_RESULTS;

	public ClideContext(final Path projectRoot, final JdtlsSession session, Collection<Command> commands) {
		this.projectRoot = projectRoot;
		this.session = session;
		this.transactions = new TransactionStack(projectRoot);

		for (final Command command : commands) {
			final String keyword = command.getKeyword();
			if (keyword == null)
				throw new IllegalStateException(
						command.getClass().getName() + " has no @Keyword on its no-arg constructor");
			if (commandsByKeywords.containsKey(keyword))
				throw new IllegalStateException("Duplicate @Keyword \"" + keyword + "\": "
						+ commandsByKeywords.get(keyword).getClass().getName() + " and " + command.getClass().getName());

			commandsByKeywords.put(keyword, command);
		}

	}

	public Collection<Command> getAllCommands() {
		return commandsByKeywords.values();
	}

	public Command getCommand(String keyword) {
		return commandsByKeywords.get(keyword);
	}

	/**
	 * Root of the project this daemon owns - every relative file path in a
	 * ParamType.POSITION notation (see PositionParser.parse()) resolves against this,
	 * never against the daemon process' own current directory.
	 */
	public Path getProjectRoot() {
		return projectRoot;
	}

	public JdtlsSession getCurrentSession() {
		return session;
	}

	/** The stack of currently-open transactions for this project - see TransactionStack, CLAUDE.md. */
	public TransactionStack getTransactions() {
		return transactions;
	}

	/**
	 * Stops the jdtls session. Safe to call more than once, and safe to follow
	 * later with JdtlsSession.start()/build() to bring it back up - see
	 * ClideDaemon.ensureSessionReady().
	 */
	public void stopSession() {
		session.stop();
	}

	/**
	 * "exit"/"quit": end the current client connection - the clide daemon (and its
	 * jdtls session, restarted lazily on demand) stay up for the next one.
	 */
	public void requestDisconnect() {
		disconnectRequested = true;
	}

	public boolean isDisconnectRequested() {
		return disconnectRequested;
	}


	/**
	 * The print mode of the connection currently being served - AI unless that
	 * client announced otherwise (see ClideDaemon.readPrintMode()). A command
	 * reads this when its output should differ for a human and for a machine;
	 * HelpCommand is the one that does today.
	 */
	public PrintMode getPrintMode() {
		return printMode;
	}

	/**
	 * Set at the start of every new connection, once its handshake has been read
	 * - see ClideDaemon.runSession().
	 */
	public void setPrintMode(final PrintMode printMode) {
		this.printMode = printMode;
	}

	/**
	 * How many entries the commands that answer with a list return at most - see
	 * Listing, and set_max_results to change it.
	 *
	 * A setting of the connection being served, not of the daemon: it goes back to
	 * DEFAULT_MAX_RESULTS at the start of every connection (see
	 * resetPerConnectionSettings()), exactly as printMode is re-read from every
	 * handshake. Inheriting a cap somebody else set in an earlier session, with no
	 * way to notice it had been set, would be a fine way to read a truncated
	 * answer as a complete one.
	 */
	public int getMaxResults() {
		return maxResults;
	}

	public void setMaxResults(final int maxResults) {
		if (maxResults < 0)
			throw new IllegalArgumentException("maxResults must not be negative: " + maxResults);

		if (maxResults > MAX_RESULTS_CEILING)
			throw new IllegalArgumentException("maxResults must not exceed " + MAX_RESULTS_CEILING);

		this.maxResults = maxResults;
	}

	/**
	 * Puts back everything a connection is allowed to change for itself alone -
	 * called by ClideDaemon.serveOneClient() before a new client is served. Today:
	 * the disconnect flag an earlier exit/quit may have left set, and maxResults.
	 */
	public void resetPerConnectionSettings() {
		disconnectRequested = false;
		maxResults = DEFAULT_MAX_RESULTS;
	}

	/** "terminate": end this connection and shut the whole daemon down. */
	public void requestShutdown() {
		shutdownRequested = true;
	}

	public boolean isShutdownRequested() {
		return shutdownRequested;
	}

}
