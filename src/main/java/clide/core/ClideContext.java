package clide.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clide.JdtlsSession;

/**
 * Mutable state shared across command executions within a single clide run:
 * one jdtls session per opened project, which one is "current" (the target
 * of print_diagnostics), the list of registered commands (for help), and
 * whether the shell should stop reading further input.
 */
public class ClideContext {

	private final Map<Path, JdtlsSession> sessions = new LinkedHashMap<>();
	private final List<Command> commands;
	private JdtlsSession currentSession;
	private boolean exitRequested;

	public ClideContext(final List<Command> commands) {
		this.commands = commands;
	}

	public List<Command> getCommands() {
		return commands;
	}

	public Map<Path, JdtlsSession> getSessions() {
		return sessions;
	}

	public JdtlsSession getCurrentSession() {
		return currentSession;
	}

	public void setCurrentSession(final JdtlsSession session) {
		currentSession = session;
	}

	/** Stops every jdtls session still tracked, then forgets them. Safe to call more than once. */
	public void stopAllSessions() {
		for (final JdtlsSession session : sessions.values())
			session.stop();

		sessions.clear();
	}

	public void requestExit() {
		exitRequested = true;
	}

	public boolean isExitRequested() {
		return exitRequested;
	}

}
