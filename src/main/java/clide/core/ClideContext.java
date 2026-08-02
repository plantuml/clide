package clide.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import clide.jdtls.JdtlsSession;

/**
 * State shared across every command execution for the lifetime of the clide
 * daemon: the project's root (what a ParamType.POSITION notation's relative
 * file path resolves against - see Position.parse() - never the daemon process' own
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
 */
public class ClideContext {

	private final Map<String, Command> commandsByKeywords = new TreeMap<>();

	private final Path projectRoot;
	private final JdtlsSession session;
	private final TransactionStack transactions;
	private boolean shutdownRequested;
	private boolean disconnectRequested;

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
	 * ParamType.POSITION notation (see Position.parse()) resolves against this,
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
	 * Reset at the start of every new connection - see
	 * ClideDaemon.serveOneClient().
	 */
	public void clearDisconnectRequested() {
		disconnectRequested = false;
	}

	/** "terminate": end this connection and shut the whole daemon down. */
	public void requestShutdown() {
		shutdownRequested = true;
	}

	public boolean isShutdownRequested() {
		return shutdownRequested;
	}

}
