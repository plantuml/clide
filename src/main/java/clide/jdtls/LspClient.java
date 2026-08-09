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

import clide.core.Monomorphic;
import clide.json.Json;

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
	private final Map<Long, BlockingQueue<Monomorphic>> pendingResponses = new ConcurrentHashMap<>();
	private final BlockingQueue<Monomorphic> notifications = new ArrayBlockingQueue<>(1000);
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

	/**
	 * params is a Monomorphic like any other JSON value, so a request whose
	 * params is a boolean (java/buildWorkspace) or absent (shutdown) says so
	 * with Monomorphic.createBoolean(true)/createNull() rather than by handing
	 * an Object the writer might not know how to serialize.
	 */
	public Monomorphic request(final String method, final Monomorphic params, final long timeoutSeconds)
			throws IOException, InterruptedException, TimeoutException {
		final long id = nextId.getAndIncrement();
		final BlockingQueue<Monomorphic> queue = new ArrayBlockingQueue<>(1);
		pendingResponses.put(id, queue);

		final Monomorphic message = Monomorphic.mapBuilder() //
				.putString("jsonrpc", "2.0") //
				.putNumber("id", id) //
				.putString("method", method) //
				.put("params", params) //
				.build();
		send(message);

		try {
			final Monomorphic response = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
			if (response == null)
				throw new TimeoutException(
						"No response for " + method + " (id=" + id + ") after " + timeoutSeconds + "s");

			return response;
		} finally {
			pendingResponses.remove(id);
		}
	}

	public void notify(final String method, final Monomorphic params) throws IOException {
		final Monomorphic message = Monomorphic.mapBuilder() //
				.putString("jsonrpc", "2.0") //
				.putString("method", method) //
				.put("params", params) //
				.build();
		send(message);
	}

	private synchronized void send(final Monomorphic message) throws IOException {
		final byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
		final String header = "Content-Length: " + body.length + "\r\n\r\n";
		serverInput.write(header.getBytes(StandardCharsets.UTF_8));
		serverInput.write(body);
		serverInput.flush();
	}

	// ------------------------------------------------------------------
	// Incoming
	// ------------------------------------------------------------------

	/** Notifications received from the server (method + params), FIFO order. */
	public BlockingQueue<Monomorphic> notifications() {
		return notifications;
	}

	public void close() {
		closed = true;
	}

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
				final Monomorphic message = Json.parse(new String(body, StandardCharsets.UTF_8));
				if (message.isMap() == false)
					continue;

				dispatch(message);
			}
		} catch (final IOException e) {
			// Server stream closed (process exited) - stop reading quietly.
		}
	}

	private void dispatch(final Monomorphic message) {
		if (isResponse(message)) {
			final BlockingQueue<Monomorphic> queue = pendingResponses.get(message.getFromMap("id").asLong());
			if (queue != null)
				queue.offer(message);

		} else if (isServerRequest(message)) {
			refuse(message);

		} else {
			notifications.offer(message);
		}
	}

	/**
	 * A server-to-client <em>request</em> - a message carrying both a method and
	 * an id, which JSON-RPC says must be answered. workspace/applyEdit is the one
	 * clide would meet first (see CLAUDE.md), but window/showMessageRequest,
	 * client/registerCapability and workspace/configuration have the same shape.
	 *
	 * Told apart from a notification by the id alone: a notification carries a
	 * method and no id, and is the only thing the notifications queue was ever
	 * meant to hold. Without this branch an incoming request landed in that queue,
	 * where nothing ever answers - so jdtls waited for a reply that was never
	 * coming, silently, for as long as the daemon lived.
	 */
	private static boolean isServerRequest(final Monomorphic message) {
		if (message.containsKey("method") == false)
			return false;

		return message.containsKey("id");
	}

	/**
	 * Answers a server request clide does not implement with JSON-RPC's own
	 * MethodNotFound (-32601), so jdtls learns straight away that this client will
	 * not do it and carries on. Saying no is the point: a refusal jdtls can read is
	 * worth more than a request left hanging, and the day clide does implement one
	 * of these, it grows a branch above this one rather than replacing it.
	 *
	 * The id is echoed as the value that came in, never re-read as a number:
	 * JSON-RPC allows a string id too, and a reply carrying a different id than
	 * the request would go unmatched on the server side.
	 */
	private void refuse(final Monomorphic message) {
		final Monomorphic error = Monomorphic.mapBuilder() //
				.putNumber("code", -32601) //
				.putString("message", "clide does not implement " + message.getFromMap("method").asString()) //
				.build();
		final Monomorphic reply = Monomorphic.mapBuilder() //
				.putString("jsonrpc", "2.0") //
				.put("id", message.getFromMap("id")) //
				.put("error", error) //
				.build();
		try {
			send(reply);
		} catch (final IOException e) {
			// Server stream already gone - the reader loop is about to stop anyway.
		}
	}

	/**
	 * Whether message is a response to a request this client is waiting on, as
	 * opposed to a server-to-client notification. The id has to be a number:
	 * JSON-RPC answers an unparseable request with an error carrying a null id,
	 * which matches no pending request and would only throw here.
	 */
	private static boolean isResponse(final Monomorphic message) {
		if (message.containsKey("result") == false && message.containsKey("error") == false)
			return false;

		return message.getFromMapOrDefault("id", Monomorphic.createNull()).isNumber();
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
