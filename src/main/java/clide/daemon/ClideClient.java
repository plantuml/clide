package clide.daemon;

import java.io.File;
import java.io.FilterInputStream;
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

import clide.PrintMode;
import clide.jdtls.EclipseProjectFiles;

/**
 * The short-lived side of clide: makes sure a daemon is running for the given
 * project - reusing one already up (see .clide/tmp/.clide.lock/DaemonLock),
 * or starting one detached in the background otherwise - then relays this
 * process' own stdin/stdout to it verbatim until stdin runs dry, and
 * disconnects. All the actual command handling happens on the other end of
 * the socket, in ClideDaemon; this class never parses a single clide command
 * itself.
 *
 * Disconnecting here never stops the daemon, and "exit"/"quit" only stop the
 * jdtls session while leaving the daemon itself up (see DisconnectCommand) -
 * only "terminate" shuts the whole daemon down, from the daemon's side. That
 * is the whole point: many short "clide &lt;project&gt;" runs in a row reuse
 * the same warm jdtls session instead of paying its startup/build cost again
 * each time.
 *
 * The one thing this class ever sends on its own behalf, rather than relaying,
 * is the handshake naming what kind of connection this is - see announceMode().
 * That handshake is what makes "clide --human" (and "clide --lua") a property of
 * this one connection rather than of the daemon every other client shares.
 *
 * A "--lua" run relays a file instead of a keyboard: same daemon, same socket,
 * same half-close at the end - only the source of the bytes differs. The Lua
 * runtime is on the other side (see LuaBridge), so nothing about running a
 * script belongs here beyond choosing what to send.
 */
public final class ClideClient {

	private static final int BOOT_TIMEOUT_SECONDS = 300; // generous - see JdtlsSession's own handshake/build budget
	private static final int POLL_INTERVAL_MILLIS = 500;

	/**
	 * Both the command-line flag and, indirectly, the policy it selects: refuse
	 * to boot a fresh daemon in place of one that used to answer here and no
	 * longer does (DaemonLock.State.DEAD), rather than silently paying its full
	 * startup/build cost again. Positional-free, like PrintMode.HUMAN_FLAG -
	 * "clide --require-live-daemon &lt;project&gt;" and
	 * "clide &lt;project&gt; --require-live-daemon" both work.
	 *
	 * Deliberately does not touch the ABSENT case: a project that has never had
	 * a daemon, or whose lock was removed cleanly, still boots one exactly as
	 * before. Only DEAD - a daemon that stopped answering without saying so -
	 * is refused, since that is the one case where continuing on is masking a
	 * question ("why did it die?") instead of answering it.
	 */
	public static final String REQUIRE_LIVE_DAEMON_FLAG = "--require-live-daemon";

	private final Path projectRoot;
	private final PrintMode printMode;
	private final boolean requireLiveDaemon;

	/** The Lua script to send, or null for an ordinary command session. */
	private final Path scriptPath;

	public ClideClient(final Path projectRoot, final PrintMode printMode) {
		this(projectRoot, printMode, false);
	}

	public ClideClient(final Path projectRoot, final PrintMode printMode, final boolean requireLiveDaemon) {
		this(projectRoot, printMode, requireLiveDaemon, null);
	}

	/** "clide --lua &lt;script&gt; &lt;project&gt;" - see announceMode()/relay(). */
	public ClideClient(final Path projectRoot, final Path scriptPath) {
		this(projectRoot, scriptPath, false);
	}

	public ClideClient(final Path projectRoot, final Path scriptPath, final boolean requireLiveDaemon) {
		this(projectRoot, PrintMode.AI, requireLiveDaemon, scriptPath);
	}

	private ClideClient(final Path projectRoot, final PrintMode printMode, final boolean requireLiveDaemon,
			final Path scriptPath) {
		this.projectRoot = projectRoot;
		this.printMode = printMode;
		this.requireLiveDaemon = requireLiveDaemon;
		this.scriptPath = scriptPath;
	}

	public void run() throws IOException, InterruptedException {
		final DaemonLock daemon = ensureDaemon();
		System.out.println("*** clide connected to daemon (pid " + daemon.pid() + ") for " + projectRoot);

		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), daemon.port())) {
			announceMode(socket);
			relay(socket);
		}
	}

	/**
	 * Sends this connection's handshake line, when it has one: PrintMode.HUMAN_FLAG
	 * for a "--human" session, ConnectionMode.SCRIPT_FLAG for a "--lua" one, before
	 * anything else is relayed.
	 *
	 * Nothing at all is sent in the default AI mode, on purpose: an AI session's
	 * byte stream stays a bare sequence of commands with no preamble to strip, and
	 * a daemon whose first line is neither flag simply treats that line as the
	 * command it is - see ConnectionMode.of() and ClideDaemon.runSession().
	 */
	private void announceMode(final Socket socket) throws IOException {
		final String flag = handshake();
		if (flag == null)
			return;

		final OutputStream out = socket.getOutputStream();
		out.write((flag + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private String handshake() {
		if (scriptPath != null)
			return ConnectionMode.SCRIPT_FLAG;

		return printMode == PrintMode.HUMAN ? PrintMode.HUMAN_FLAG : null;
	}

	private DaemonLock ensureDaemon() throws IOException, InterruptedException {
		final DaemonLock probed = DaemonLock.probe(projectRoot);
		if (probed.state() == DaemonLock.State.LIVE)
			return probed;

		if (probed.state() == DaemonLock.State.DEAD && requireLiveDaemon)
			throw new IOException(deadDaemonRefusal(probed));

		// Same directory as .clide.lock (EclipseProjectFiles.STAGING_DIR) - created
		// here since it must exist before the Redirect.appendTo() below can work,
		// and nothing earlier in this process is guaranteed to have created it yet.
		final Path stagingDir = EclipseProjectFiles.stagingDir(projectRoot);
		Files.createDirectories(stagingDir);

		final Path logFile = stagingDir.resolve(".clide-daemon.log");
		deleteStaleFiles(logFile);

		final Process daemonProcess = startDetachedDaemon(logFile);
		return awaitDaemon(daemonProcess, logFile);
	}

	/**
	 * The message a DEAD probe plus --require-live-daemon refuses with: the pid
	 * that used to answer here, and where to look next. Deliberately one line
	 * and led with "clide daemon for &lt;project&gt;", matching every other
	 * IOException this class throws (see deleteOrFail(), awaitDaemon()), so a
	 * script or a person grepping a batch of runs for failures sees one
	 * consistent shape regardless of which of them fired.
	 */
	private String deadDaemonRefusal(final DaemonLock probed) {
		final Path logFile = EclipseProjectFiles.stagingDir(projectRoot).resolve(".clide-daemon.log");
		return "clide daemon for " + projectRoot + " is dead (last pid " + probed.pid() + ", was on port "
				+ probed.port() + ") and " + REQUIRE_LIVE_DAEMON_FLAG + " forbids starting a replacement - see "
				+ logFile + " for why it stopped, or drop " + REQUIRE_LIVE_DAEMON_FLAG
				+ " to let clide restart it automatically";
	}

	/**
	 * Deletes any leftover .clide.lock (stale - the probe() above already ruled
	 * out a live one) and .clide-daemon.log from a previous daemon, before
	 * booting a fresh one. The log in particular must go: it's opened in append
	 * mode (see startDetachedDaemon()) and awaitDaemon()'s tailing always starts
	 * reading at byte 0, so a leftover log from earlier runs would otherwise get
	 * replayed in full on every fresh start. Unlike a stale lock (harmless to
	 * leave behind - the next probe() call would just find it DEAD and ignore
	 * it), failing to delete here is fatal: silently starting a fresh daemon on
	 * top of leftover files isn't safe, so this throws instead of swallowing the
	 * error.
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

			final DaemonLock probed = DaemonLock.probe(projectRoot);
			if (probed.state() == DaemonLock.State.LIVE) {
				tailLog(logFile, logBytesRead); // catch "Daemon ready on port ..." - printed right after the lock file
				return probed;
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

		try (InputStream source = source()) {
			pump(source, socket.getOutputStream());
		}
		// Tells the daemon this client has nothing more to send. For a script that
		// is not a nicety but the protocol: the daemon reads a script to EOF, and
		// this half-close is that EOF - the read direction stays open for whatever
		// the script prints back. See ClideDaemon.runScript().
		socket.shutdownOutput();

		output.join(); // in practice unreachable - output's own System.exit(0) ends the process first
	}

	/**
	 * What this client relays: the script file for a "--lua" run, this process'
	 * own stdin otherwise. System.in is wrapped in a stream whose close() does
	 * nothing, so the try-with-resources in relay() can treat both the same
	 * without ever closing the real stdin.
	 */
	private InputStream source() throws IOException {
		if (scriptPath != null)
			return Files.newInputStream(scriptPath);

		return new FilterInputStream(System.in) {
			@Override
			public void close() {
				// stdin is not ours to close
			}
		};
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
