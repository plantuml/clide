package clide.daemon;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes .clide.lock, the file a fresh clide invocation checks
 * before doing anything else to find out whether a daemon is already running
 * for a given project - see ClideClient/ClideDaemon. Holds exactly what a
 * client needs to reach that daemon: the local TCP port it listens on, plus
 * its PID for diagnostics (not trusted alone to prove liveness - PIDs get
 * reused by the OS, see isReachable()).
 */
public final class DaemonLock {

	private static final String FILE_NAME = ".clide.lock";
	private static final int PROBE_TIMEOUT_MILLIS = 300;

	private final int port;
	private final long pid;

	private DaemonLock(final int port, final long pid) {
		this.port = port;
		this.pid = pid;
	}

	public int port() {
		return port;
	}

	public long pid() {
		return pid;
	}

	public static Path file(final Path projectRoot) {
		return projectRoot.resolve(FILE_NAME);
	}

	/** Writes the lock for the daemon running as the current JVM process. */
	public static void write(final Path projectRoot, final int port) throws IOException {
		final long pid = ProcessHandle.current().pid();
		Files.writeString(file(projectRoot), port + "\n" + pid + "\n", StandardCharsets.UTF_8);
	}

	/**
	 * Best effort: a lock left behind after a crash is harmless, the next
	 * readIfLive() call finds it stale (unreachable port) and ignores it anyway.
	 */
	public static void delete(final Path projectRoot) {
		try {
			Files.deleteIfExists(file(projectRoot));
		} catch (final IOException e) {
			// nothing more we can do - see method comment
		}
	}

	/**
	 * Returns the lock left by a previous daemon for this project, but only if it
	 * still actually answers on its recorded port - a lock whose daemon crashed,
	 * was killed, or never got a chance to clean up after itself (e.g. machine
	 * reboot) is stale and treated exactly like "no daemon running". Returns null
	 * in every case that isn't "a live daemon is reachable".
	 */
	public static DaemonLock readIfLive(final Path projectRoot) {
		final Path file = file(projectRoot);
		if (Files.isRegularFile(file) == false)
			return null;

		final DaemonLock lock = parse(file);
		if (lock == null)
			return null;

		return lock.isReachable() ? lock : null;
	}

	private static DaemonLock parse(final Path file) {
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
			return new DaemonLock(port, pid);
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	private boolean isReachable() {
		try (Socket probe = new Socket()) {
			probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MILLIS);
			return true;
		} catch (final IOException e) {
			return false;
		}
	}

}
