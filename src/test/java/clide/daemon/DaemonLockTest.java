package clide.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de DaemonLock - le fichier .clide/tmp/.clide.lock, et la seule chose
 * qui compte à son sujet : dire à un client, avant qu'il ne se connecte pour
 * de vrai, si ce qu'il nomme est encore vivant.
 *
 * clide.py réimplémente cette même logique côté Python (voir son propre
 * read_lock()/probe()) plutôt que d'appeler ce code - ces tests sont donc la
 * seule vérification, côté Java, du format que les deux doivent continuer à
 * s'accorder sur : deux lignes, port puis pid.
 */
class DaemonLockTest {

	@Test
	@DisplayName("aucun fichier lock : ABSENT")
	void absentWhenNoLockFile(@TempDir final Path projectRoot) {
		final DaemonLock lock = DaemonLock.probe(projectRoot);
		assertEquals(DaemonLock.State.ABSENT, lock.state());
		assertEquals(0, lock.port());
		assertEquals(0, lock.pid());
	}

	@Test
	@DisplayName("un port qui répond : LIVE")
	void liveWhenPortAnswers(@TempDir final Path projectRoot) throws IOException {
		try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			DaemonLock.write(projectRoot, listening.getLocalPort());

			final DaemonLock lock = DaemonLock.probe(projectRoot);
			assertEquals(DaemonLock.State.LIVE, lock.state());
			assertEquals(listening.getLocalPort(), lock.port());
			assertEquals(ProcessHandle.current().pid(), lock.pid());
		}
	}

	@Test
	@DisplayName("un lock qui nomme un port fermé : DEAD, pas ABSENT")
	void deadWhenPortDoesNotAnswer(@TempDir final Path projectRoot) throws IOException {
		final int closedPort;
		try (ServerSocket freedImmediately = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = freedImmediately.getLocalPort();
		}
		DaemonLock.write(projectRoot, closedPort);

		final DaemonLock lock = DaemonLock.probe(projectRoot);
		assertEquals(DaemonLock.State.DEAD, lock.state());
		assertEquals(closedPort, lock.port());
		assertEquals(ProcessHandle.current().pid(), lock.pid());
	}

	@Test
	@DisplayName("delete() ramène à ABSENT")
	void deleteBringsBackToAbsent(@TempDir final Path projectRoot) throws IOException {
		try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			DaemonLock.write(projectRoot, listening.getLocalPort());
			DaemonLock.delete(projectRoot);

			assertEquals(DaemonLock.State.ABSENT, DaemonLock.probe(projectRoot).state());
		}
	}

	@Test
	@DisplayName("delete() sans fichier ne casse rien")
	void deleteWithNothingThereIsANoOp(@TempDir final Path projectRoot) {
		DaemonLock.delete(projectRoot); // must not throw
		assertEquals(DaemonLock.State.ABSENT, DaemonLock.probe(projectRoot).state());
	}

	@Test
	@DisplayName("un contenu illisible se lit ABSENT, pas DEAD : rien d'exploitable n'a jamais été écrit")
	void unparseableContentIsAbsent(@TempDir final Path projectRoot) throws IOException {
		final Path lockFile = DaemonLock.file(projectRoot);
		Files.createDirectories(lockFile.getParent());
		Files.writeString(lockFile, "not a lock file\n", StandardCharsets.UTF_8);

		assertEquals(DaemonLock.State.ABSENT, DaemonLock.probe(projectRoot).state());
	}

}
