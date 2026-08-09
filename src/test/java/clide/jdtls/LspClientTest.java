package clide.jdtls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.core.Monomorphic;
import clide.json.Json;

/**
 * Tests du tri que fait LspClient entre les trois choses qu'un serveur LSP
 * peut lui envoyer : une réponse à une requête en attente, une notification,
 * et — le cas ajouté ici — une *requête* du serveur vers le client.
 *
 * Cette troisième catégorie était auparavant confondue avec la deuxième : un
 * message portant une méthode et un id partait dans la file des
 * notifications, où rien ne répond jamais. jdtls attendait alors une réponse
 * qui ne venait pas, sans le dire, aussi longtemps que vivait le daemon.
 * `workspace/applyEdit` est celle que clide rencontrerait en premier (voir
 * CLAUDE.md), mais `window/showMessageRequest` et `workspace/configuration`
 * ont la même forme.
 *
 * Le contrat testé n'est pas « clide sait faire applyEdit » — il ne sait pas,
 * et ne l'annonce pas dans initialize — mais « clide répond non ». Un refus
 * que jdtls peut lire vaut mieux qu'une requête laissée en suspens.
 */
class LspClientTest {

	private static final long REPLY_TIMEOUT_MS = 2000;

	@Test
	@DisplayName("une requête du serveur reçoit un MethodNotFound, avec le même id")
	void aServerRequestGetsAMethodNotFoundReply() throws Exception {
		final Fixture fixture = new Fixture();

		fixture.sendToClient("""
				{"jsonrpc": "2.0", "id": 42, "method": "workspace/applyEdit", "params": {"edit": {}}}""");

		final Monomorphic reply = fixture.awaitReply();
		assertEquals(42, reply.getFromMap("id").asLong());
		assertEquals(-32601, reply.getFromMap("error").getFromMap("code").asLong());
		assertTrue(reply.getFromMap("error").getFromMap("message").asString().contains("workspace/applyEdit"));
	}

	@Test
	@DisplayName("une requête du serveur ne part pas dans la file des notifications")
	void aServerRequestDoesNotLandInTheNotificationQueue() throws Exception {
		final Fixture fixture = new Fixture();

		fixture.sendToClient("""
				{"jsonrpc": "2.0", "id": 1, "method": "window/showMessageRequest", "params": {}}""");
		fixture.awaitReply();

		assertNull(fixture.client.notifications().poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	@DisplayName("un id texte est renvoyé tel quel, pas relu comme un nombre")
	void aStringIdIsEchoedAsAString() throws Exception {
		final Fixture fixture = new Fixture();

		fixture.sendToClient("""
				{"jsonrpc": "2.0", "id": "req-7", "method": "workspace/configuration", "params": {}}""");

		assertEquals("req-7", fixture.awaitReply().getFromMap("id").asString());
	}

	@Test
	@DisplayName("une notification reste une notification, et n'appelle aucune réponse")
	void aNotificationIsStillQueuedAndUnanswered() throws Exception {
		final Fixture fixture = new Fixture();

		fixture.sendToClient("""
				{"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics", "params": {"uri": "file:///a.java"}}""");

		final Monomorphic notification = fixture.client.notifications().poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		assertEquals("textDocument/publishDiagnostics", notification.getFromMap("method").asString());
		assertEquals(0, fixture.written.size());
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	/**
	 * Un LspClient branché sur deux tuyaux plutôt que sur un vrai processus
	 * jdtls : ce qu'il écrit s'accumule dans written, ce qu'on lui envoie passe
	 * par serverSide. Le cadrage JSON-RPC (l'en-tête Content-Length) est celui
	 * du protocole, écrit ici à la main pour que le test parle le même langage
	 * que le serveur qu'il remplace.
	 */
	private static final class Fixture {

		private final ByteArrayOutputStream written = new ByteArrayOutputStream();
		private final PipedOutputStream serverSide = new PipedOutputStream();
		private final LspClient client;

		private Fixture() throws IOException {
			final PipedInputStream clientSide = new PipedInputStream(serverSide);
			this.client = new LspClient(written, clientSide);
		}

		private void sendToClient(final String json) throws IOException {
			final byte[] body = json.getBytes(StandardCharsets.UTF_8);
			serverSide.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			serverSide.write(body);
			serverSide.flush();
		}

		/** Le message que le client a écrit en retour, une fois arrivé - ou un échec au bout du délai. */
		private Monomorphic awaitReply() throws InterruptedException {
			final long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
			while (System.currentTimeMillis() < deadline) {
				final String raw = written.toString(StandardCharsets.UTF_8);
				final int separator = raw.indexOf("\r\n\r\n");
				if (separator >= 0)
					return Json.parse(raw.substring(separator + 4));

				Thread.sleep(10);
			}
			throw new AssertionError("aucune réponse écrite au bout de " + REPLY_TIMEOUT_MS + " ms");
		}

	}

}
