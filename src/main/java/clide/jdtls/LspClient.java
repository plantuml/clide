package clide.jdtls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal LSP client: JSON-RPC framing (Content-Length headers) over a
 * process's stdin/stdout, request/response correlation, and a queue of
 * server-to-client notifications (in particular
 * textDocument/publishDiagnostics).
 */
public class LspClient {

	private final OutputStream serverInput;
	private final InputStream serverOutput;
	private final AtomicLong nextId = new AtomicLong(1);
	private final Map<Long, BlockingQueue<Truc>> pendingResponses = new ConcurrentHashMap<>();
	private final BlockingQueue<Truc> notifications = new ArrayBlockingQueue<>(1000);
	private final Thread readerThread;
	private volatile boolean closed;

	public LspClient(final OutputStream serverInput, final InputStream serverOutput) {
		this.serverInput = serverInput;
		this.serverOutput = serverOutput;
		this.readerThread = new Thread(this::readLoop, "lsp-reader");
		this.readerThread.setDaemon(true);
		this.readerThread.start();
	}

	// ------------------------------------------------------------------
	// Outgoing
	// ------------------------------------------------------------------

	public Truc request(final String method, final Object params, final long timeoutSeconds)
			throws IOException, InterruptedException, TimeoutException {
		final long id = nextId.getAndIncrement();
		final BlockingQueue<Truc> queue = new ArrayBlockingQueue<>(1);
		pendingResponses.put(id, queue);

		final Truc message = new Truc();
		message.putString("jsonrpc", "2.0");
		message.putLong("id", id);
		message.putString("method", method);
		message.putObject("params", params);
		send(message);

		try {
			final Truc response = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
			if (response == null)
				throw new TimeoutException(
						"No response for " + method + " (id=" + id + ") after " + timeoutSeconds + "s");

			return response;
		} finally {
			pendingResponses.remove(id);
		}
	}

	public void notify(final String method, final Truc params) throws IOException {
		final Truc message = new Truc();
		message.putString("jsonrpc", "2.0");
		message.putString("method", method);
		message.putTruc("params", params);
		send(message);
	}

	private synchronized void send(final Truc message) throws IOException {
		final byte[] body = Json.writeTruc(message).getBytes(StandardCharsets.UTF_8);
		final String header = "Content-Length: " + body.length + "\r\n\r\n";
		serverInput.write(header.getBytes(StandardCharsets.UTF_8));
		serverInput.write(body);
		serverInput.flush();
	}

	// ------------------------------------------------------------------
	// Incoming
	// ------------------------------------------------------------------

	/** Notifications received from the server (method + params), FIFO order. */
	public BlockingQueue<Truc> notifications() {
		return notifications;
	}

	public void close() {
		closed = true;
	}

	@SuppressWarnings("unchecked")
	private void readLoop() {
		try {
			while (closed == false) {
				final Map<String, String> headers = readHeaders();
				if (headers == null)
					return;

				final int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
				if (length == 0)
					continue;

				final byte[] body = readExactly(length);
				final Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
				if (parsed instanceof Map == false)
					continue;

				final Truc message = Truc.fromMap((Map<String, Object>) parsed);
				dispatch(message);
			}
		} catch (final IOException e) {
			// Server stream closed (process exited) - stop reading quietly.
		}
	}

	private void dispatch(final Truc message) {
		if (message.containsKey("id") && (message.containsKey("result") || message.containsKey("error"))) {
			final long id = message.getAsLongOrMinusOn("id", -1);
			final BlockingQueue<Truc> queue = pendingResponses.get(id);
			if (queue != null)
				queue.offer(message);

		} else {
			notifications.offer(message);
		}
	}

	private Map<String, String> readHeaders() throws IOException {
		final Map<String, String> headers = new HashMap<>();
		while (true) {
			final String line = readLine();
			if (line == null)
				return headers.isEmpty() ? null : headers;

			if (line.isEmpty())
				return headers;

			final int colon = line.indexOf(':');
			if (colon > 0)
				headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
						line.substring(colon + 1).trim());

		}
	}

	private String readLine() throws IOException {
		final StringBuilder line = new StringBuilder();
		int b;
		boolean readAny = false;
		while ((b = serverOutput.read()) != -1) {
			readAny = true;
			if (b == '\n')
				break;

			if (b != '\r')
				line.append((char) b);

		}
		if (readAny == false)
			return null;

		return line.toString();
	}

	private byte[] readExactly(final int length) throws IOException {
		final byte[] buffer = new byte[length];
		int read = 0;
		while (read < length) {
			final int n = serverOutput.read(buffer, read, length - read);
			if (n == -1)
				throw new IOException("Server stream closed while reading message body");

			read += n;
		}
		return buffer;
	}

	/** Thrown when a request does not get a response within the given timeout. */
	public static class TimeoutException extends Exception {
		public TimeoutException(final String message) {
			super(message);
		}
	}

}
