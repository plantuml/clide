package clide.daemon;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import clide.jdtls.EclipseProjectFiles;

/**
 * Reads and writes .clide/tmp/.clide.lock, the file a client checks before
 * doing anything else to find out whether a daemon is already running for a
 * given project - written by ClideDaemon.run(), read by clide.py (see
 * CLAUDE.md). Holds exactly what a client needs to reach that daemon: the
 * local TCP port it listens on, plus its PID for diagnostics (not trusted
 * alone to prove liveness - PIDs get reused by the OS, see probe()). Lives
 * under .clide/tmp/ (see EclipseProjectFiles.STAGING_DIR) rather than at the
 * project root, for the same reason nothing else clide generates sits there
 * either.
 *
 * probe()/State are not read by any Java code today - clide.py has its own
 * equivalent (see its read_lock()/probe()), since it is the one thing that
 * ever needs to answer "is a daemon already running here" now that nothing on
 * the Java side auto-starts one. Kept here anyway, alongside write()/delete()
 * which ClideDaemon does still call, as the one place the lock file's own
 * format and semantics are specified precisely enough to test - see
 * DaemonLockTest.
 */
public final class DaemonLock {

	/**
	 * What the last probe() found. ABSENT and DEAD both mean "nothing to
	 * connect to", but a caller deciding what to tell the user needs to tell
	 * them apart: ABSENT is the ordinary first-run-for-this-project case, while
	 * DEAD means a daemon started here before and stopped answering without
	 * cleaning up its lock (crash, kill, machine reboot) - worth saying so
	 * explicitly rather than folding both into a single boolean, since nothing
	 * on the Java side auto-starts a replacement either way anymore (see
	 * clide.py's own probe(), the one place this distinction still drives a
	 * decision).
	 */
	public enum State {
		/** No lock file at all - nothing has ever written one, or it was removed cleanly. */
		ABSENT,
		/** A lock file exists but its recorded port doesn't answer - the daemon that wrote it is gone. */
		DEAD,
		/** A lock file exists and its recorded port answers. */
		LIVE
	}

	private static final String FILE_NAME = ".clide.lock";
	private static final int PROBE_TIMEOUT_MILLIS = 300;

	private final State state;
	private final int port;
	private final long pid;

	private DaemonLock(final State state, final int port, final long pid) {
		this.state = state;
		this.port = port;
		this.pid = pid;
	}

	public State state() {
		return state;
	}

	/** Meaningful only when state() is LIVE - 0 otherwise. */
	public int port() {
		return port;
	}

	/**
	 * The pid a lock file named: the daemon's own, still-running pid when
	 * state() is LIVE; the pid of the daemon that used to answer here, when
	 * DEAD; 0 when ABSENT, since no lock means no pid was ever recorded.
	 */
	public long pid() {
		return pid;
	}

	public static Path file(final Path projectRoot) {
		return EclipseProjectFiles.stagingDir(projectRoot).resolve(FILE_NAME);
	}

	/**
	 * Writes the lock for the daemon running as the current JVM process. Creates
	 * .clide/tmp/ if it is not there yet - normally already true by the time this
	 * runs (EclipseProjectFiles.stage(), called from JdtlsSession.start(), creates
	 * it first), but this does not rely on that ordering.
	 */
	public static void write(final Path projectRoot, final int port) throws IOException {
		final long pid = ProcessHandle.current().pid();
		final Path file = file(projectRoot);
		Files.createDirectories(file.getParent());
		Files.writeString(file, port + "\n" + pid + "\n", StandardCharsets.UTF_8);
	}

	/**
	 * Best effort: a lock left behind after a crash is harmless, the next
	 * probe() call finds it DEAD (unreachable port) and treats it accordingly.
	 */
	public static void delete(final Path projectRoot) {
		try {
			Files.deleteIfExists(file(projectRoot));
		} catch (final IOException e) {
			// nothing more we can do - see method comment
		}
	}

	/**
	 * Reads .clide.lock and, if it names a port, probes that port to tell a
	 * merely-stale lock from a live daemon - see State. Never null: ABSENT and
	 * DEAD are both ordinary outcomes here, not read failures, so unlike the
	 * readIfLive() this replaced there is no null case left to document. A lock
	 * file that exists but can't be parsed (truncated write, foreign content) is
	 * treated as ABSENT rather than DEAD: there is no pid or port to report, so
	 * "nothing usable was ever recorded" is the more honest of the two.
	 */
	public static DaemonLock probe(final Path projectRoot) {
		final Path file = file(projectRoot);
		if (Files.isRegularFile(file) == false)
			return new DaemonLock(State.ABSENT, 0, 0);

		final long[] parsed = parse(file);
		if (parsed == null)
			return new DaemonLock(State.ABSENT, 0, 0);

		final int port = (int) parsed[0];
		final long pid = parsed[1];
		return isReachable(port) ? new DaemonLock(State.LIVE, port, pid) : new DaemonLock(State.DEAD, port, pid);
	}

	/** {port, pid} from a lock file's two lines, or null if it doesn't parse as one. */
	private static long[] parse(final Path file) {
		final List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (final IOException e) {
			return null;
		}
		if (lines.size() < 2)
			return null;

		try {
			final int port = Integer.parseInt(lines.get(0).trim());
			final long pid = Long.parseLong(lines.get(1).trim());
			return new long[] { port, pid };
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	private static boolean isReachable(final int port) {
		try (Socket probe = new Socket()) {
			probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MILLIS);
			return true;
		} catch (final IOException e) {
			return false;
		}
	}

}
