package clide.core;

import java.util.List;

import clide.jdtls.JdtlsSession;

/**
 * State shared across every command execution for the lifetime of the clide
 * daemon: the single jdtls session for this project, the list of registered
 * commands (for help), and the two distinct ways a client interaction can
 * end:
 * <ul>
 * <li>"exit"/"quit" (see DisconnectCommand) - stop the jdtls session and end
 * only the current connection. The daemon and its .clide.lock stay up; the
 * next command that actually needs jdtls restarts it lazily - see
 * ClideDaemon.ensureSessionReady().</li>
 * <li>"terminate" (see TerminateCommand) - stop the jdtls session, end the
 * connection, and shut the whole daemon down.</li>
 * </ul>
 * ClideDaemon resets isDisconnectRequested() at the start of every new
 * connection; isShutdownRequested() is one-way and checked by both the
 * per-connection loop and the daemon's own accept loop.
 */
public class ClideContext {

	private final List<Command> commands;
	private final JdtlsSession session;
	private boolean shutdownRequested;
	private boolean disconnectRequested;

	public ClideContext(final JdtlsSession session, final List<Command> commands) {
		this.commands = commands;
		this.session = session;
	}

	public List<Command> getCommands() {
		return commands;
	}

	public JdtlsSession getCurrentSession() {
		return session;
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
	 * "exit"/"quit": end the current client connection - the clide daemon (and
	 * its jdtls session, restarted lazily on demand) stay up for the next one.
	 */
	public void requestDisconnect() {
		disconnectRequested = true;
	}

	public boolean isDisconnectRequested() {
		return disconnectRequested;
	}

	/** Reset at the start of every new connection - see ClideDaemon.serveOneClient(). */
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
