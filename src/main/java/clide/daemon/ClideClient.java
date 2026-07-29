package clide.daemon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The short-lived side of clide: makes sure a daemon is running for the given
 * project - reusing one already up (see .clide.lock/DaemonLock), or starting
 * one detached in the background otherwise - then relays this process' own
 * stdin/stdout to it verbatim until stdin runs dry, and disconnects. All the
 * actual command handling happens on the other end of the socket, in
 * ClideDaemon; this class never parses a single clide command itself.
 *
 * Disconnecting here never stops the daemon, and "exit"/"quit" only stop the
 * jdtls session while leaving the daemon itself up (see DisconnectCommand) -
 * only "terminate" shuts the whole daemon down, from the daemon's side. That
 * is the whole point: many short "clide &lt;project&gt;" runs in a row reuse
 * the same warm jdtls session instead of paying its startup/build cost again
 * each time.
 */
public final class ClideClient {

	private static final int BOOT_TIMEOUT_SECONDS = 300; // generous - see JdtlsSession's own handshake/build budget
	private static final int POLL_INTERVAL_MILLIS = 500;

	private final Path projectRoot;

	public ClideClient(final Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public void run() throws IOException, InterruptedException {
		final DaemonLock daemon = ensureDaemon();
		System.out.println("*** clide connected to daemon (pid " + daemon.pid() + ") for " + projectRoot);

		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), daemon.port())) {
			relay(socket);
		}
	}

	private DaemonLock ensureDaemon() throws IOException, InterruptedException {
		final DaemonLock existing = DaemonLock.readIfLive(projectRoot);
		if (existing != null)
			return existing;

		final Path logFile = projectRoot.resolve(".clide-daemon.log");
		deleteStaleFiles(logFile);

		final Process daemonProcess = startDetachedDaemon(logFile);
		return awaitDaemon(daemonProcess, logFile);
	}

	/**
	 * Deletes any leftover .clide.lock (stale - readIfLive() above already ruled
	 * out a live one) and .clide-daemon.log from a previous daemon, before
	 * booting a fresh one. The log in particular must go: it's opened in append
	 * mode (see startDetachedDaemon()) and awaitDaemon()'s tailing always starts
	 * reading at byte 0, so a leftover log from earlier runs would otherwise get
	 * replayed in full on every fresh start. Unlike a stale lock (harmless to
	 * leave behind - the next readIfLive() call would just find it unreachable
	 * and ignore it), failing to delete here is fatal: silently starting a fresh
	 * daemon on top of leftover files isn't safe, so this throws instead of
	 * swallowing the error.
	 */
	private void deleteStaleFiles(final Path logFile) throws IOException {
		deleteOrFail(DaemonLock.file(projectRoot));
		deleteOrFail(logFile);
	}

	private void deleteOrFail(final Path file) throws IOException {
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new IOException("Could not delete " + file + " before starting a new daemon: " + e.getMessage(), e);
		}
	}

	private Process startDetachedDaemon(final Path logFile) throws IOException {
		final List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(ClideDaemon.class.getName());
		command.add(projectRoot.toString());

		final ProcessBuilder builder = new ProcessBuilder(command);
		// Redirect.DISCARD is output-only (invalid for reading) - a null-device file
		// is what actually detaches the daemon's stdin from ours.
		builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
		builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
		builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

		System.out.println("No clide daemon running yet for " + projectRoot
				+ " - starting one in the background (log: " + logFile + ") ...");
		return builder.start();
	}

	/**
	 * Polls .clide.lock until it appears (and answers), the daemon process dies
	 * first (fails fast instead of waiting out the full timeout - e.g. jdtls
	 * missing or a build error), or BOOT_TIMEOUT_SECONDS elapses. Meanwhile, tails
	 * the daemon's own log file (its stdout/stderr, redirected there since the
	 * daemon runs detached - see startDetachedDaemon()) and echoes any new content
	 * to this process' console, so the daemon's boot progress ("(1/3) ...", "(2/3)
	 * ...", "Daemon ready on port ...") is visible here as it happens, instead of
	 * only ever landing in the log file. There is no socket to the daemon yet at
	 * this point - tailing the log file is the only way to see that progress
	 * before the daemon is fully up.
	 */
	private DaemonLock awaitDaemon(final Process daemonProcess, final Path logFile)
			throws InterruptedException, IOException {
		final long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_SECONDS * 1000L;
		long logBytesRead = 0;
		while (System.currentTimeMillis() < deadline) {
			logBytesRead = tailLog(logFile, logBytesRead);

			final DaemonLock lock = DaemonLock.readIfLive(projectRoot);
			if (lock != null) {
				tailLog(logFile, logBytesRead); // catch "Daemon ready on port ..." - printed right after the lock file
				return lock;
			}

			if (daemonProcess.isAlive() == false)
				throw new IOException("clide daemon for " + projectRoot + " exited before becoming ready (exit code "
						+ daemonProcess.exitValue() + ") - see " + logFile);

			Thread.sleep(POLL_INTERVAL_MILLIS);
		}
		throw new IOException(
				"Timed out waiting for the clide daemon to become ready for " + projectRoot + " - check " + logFile);
	}

	/**
	 * Prints whatever has been appended to logFile since alreadyRead bytes were
	 * last read from it, and returns the new byte count read so far. A no-op,
	 * returning alreadyRead unchanged, if the file doesn't exist yet (the daemon
	 * process hasn't been scheduled yet) or has no new content.
	 */
	private long tailLog(final Path logFile, final long alreadyRead) throws IOException {
		if (Files.isRegularFile(logFile) == false)
			return alreadyRead;

		try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
			final long length = file.length();
			if (length <= alreadyRead)
				return alreadyRead;

			file.seek(alreadyRead);
			final byte[] newContent = new byte[(int) (length - alreadyRead)];
			file.readFully(newContent);
			System.out.print(new String(newContent, StandardCharsets.UTF_8));
			System.out.flush();

			return length;
		}
	}

	/**
	 * Pumps this process' stdin to the daemon and the daemon's replies back to
	 * this process' stdout, until stdin runs out or the daemon closes the
	 * connection (which happens right after "exit"/"quit"/"terminate", or once it
	 * has seen our own stdin's EOF).
	 *
	 * The daemon-to-client direction runs on its own thread (output) because once
	 * the *daemon* hangs up first - the common interactive case: the user typing
	 * "exit"/"quit"/"terminate" - the main thread below is still sitting in a
	 * blocking read of System.in (the keyboard), with nothing telling it the
	 * connection is already gone. It would only ever find out on the next
	 * keystroke, when relaying that line into the now-closed socket finally
	 * fails - which is exactly the "have to press Enter a few times to get my
	 * shell prompt back" symptom. Java has no reliable, portable way to
	 * interrupt a read already blocked on console input, so instead: the moment
	 * output has drained everything the daemon sent (i.e. the connection is
	 * over, from either side), it ends the whole process outright. That
	 * unblocks the stuck System.in read as a side effect of the JVM tearing
	 * down, instead of waiting for it to ever return on its own.
	 */
	private void relay(final Socket socket) throws IOException, InterruptedException {
		final Thread output = new Thread(() -> {
			pump(socketInput(socket), System.out);
			System.out.flush();
			System.exit(0); // see method doc - this is what actually frees a blocked System.in read
		}, "clide-daemon-output");
		output.setDaemon(true);
		output.start();

		pump(System.in, socket.getOutputStream());
		socket.shutdownOutput(); // tells the daemon this client has nothing more to send

		output.join(); // in practice unreachable - output's own System.exit(0) ends the process first
	}

	private InputStream socketInput(final Socket socket) {
		try {
			return socket.getInputStream();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void pump(final InputStream in, final OutputStream out) {
		try {
			in.transferTo(out);
		} catch (final IOException e) {
			// the peer closed its side - the normal end of this connection
		}
	}

	private String javaExecutable() {
		final String javaHome = System.getProperty("java.home");
		if (javaHome == null)
			return "java";

		final Path candidate = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
		return Files.isExecutable(candidate) ? candidate.toString() : "java";
	}

	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private File nullDevice() {
		return new File(isWindows() ? "NUL" : "/dev/null");
	}

}
