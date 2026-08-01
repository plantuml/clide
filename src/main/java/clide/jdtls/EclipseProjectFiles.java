package clide.jdtls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Keeps a project's own .project/.classpath - hand written, produced by
 * "gradlew eclipse", or simply absent - untouched on disk, while still
 * letting jdtls import a fixed descriptor that clide controls (source
 * folders, test markers, .clide/*.jar - see JdtlsSession.buildDotProject()/
 * buildDotClasspath()).
 *
 * The trick: stage() moves aside whichever of the two files already exist at
 * the project root (into .clide/tmp/, created if needed) and writes clide's
 * own next to them - unconditionally, unlike the ensureDotFilesPresent() this
 * replaces, which only wrote a file that was missing and otherwise left a
 * pre-existing one (and whatever real dependencies it resolved) in permanent
 * use. unstage() later puts the original back - or removes clide's own file
 * if there was nothing to restore - once jdtls has actually finished
 * importing the project, not merely once the LSP handshake has: see
 * JdtlsSession.start()/restoreEclipseFiles() for exactly when that is, and
 * why it cannot be any earlier.
 *
 * This only works because jdtls, once it has imported a project, never
 * revisits .project/.classpath on its own - confirmed by editing .classpath
 * on disk under a live daemon and observing that neither a passive wait nor
 * an explicit rebuild picked up the change; only a fresh jdtls import (a new
 * daemon) does. Swapping the files back right after the initial build is
 * therefore safe: there is nothing left watching them.
 *
 * A copy of whichever content was actually handed to jdtls this run is kept
 * at .clide/tmp/&lt;name&gt;.clide, purely for debugging - never read back by
 * unstage(), and freely overwritten by the next stage().
 */
public final class EclipseProjectFiles {

	/**
	 * clide's scratch directory for a project - also where DaemonLock's
	 * .clide.lock and ClideClient's .clide-daemon.log live, for the same reason
	 * everything here does: keep the project root itself free of anything clide
	 * did not find there already. Public so those two classes share this one
	 * definition rather than each hard-coding ".clide/tmp" themselves.
	 */
	public static final String STAGING_DIR = ".clide/tmp";

	private static final String DEBUG_SUFFIX = ".clide";
	private static final List<String> MANAGED_FILES = List.of(".project", ".classpath");

	private final Path projectRoot;
	private boolean staged;

	private EclipseProjectFiles(final Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public static EclipseProjectFiles forProject(final Path projectRoot) {
		return new EclipseProjectFiles(projectRoot);
	}

	public static Path stagingDir(final Path projectRoot) {
		return projectRoot.resolve(STAGING_DIR);
	}

	/**
	 * Refuses to start if a previous daemon crashed between stage() and
	 * unstage(), leaving a project's real .project or .classpath stranded in the
	 * staging area instead of at the project root where it belongs - same idea,
	 * and same reason, as TransactionStack.refuseIfDirty(): guessing how to put
	 * a stranded file back risks losing it if the guess is wrong, so recovery is
	 * left to the user.
	 */
	public static void refuseIfDirty(final Path projectRoot) throws IOException {
		final Path staging = stagingDir(projectRoot);
		for (final String name : MANAGED_FILES) {
			final Path stranded = staging.resolve(name);
			if (Files.exists(stranded))
				throw new IOException("Refusing to start: " + stranded + " still exists - a previous clide "
						+ "daemon likely crashed while staging " + name + ". Move it back to "
						+ projectRoot.resolve(name) + " by hand (or remove it if " + name
						+ " did not exist before clide ran) before starting again.");
		}
	}

	/**
	 * Moves .project/.classpath aside if present, writes clide's own in their
	 * place, and remembers what unstage() will need to do. Throws (and leaves
	 * whatever was already moved in .clide/tmp/, on purpose - see
	 * refuseIfDirty()) if either step fails partway.
	 */
	public void stage(final String projectXml, final String classpathXml) throws IOException {
		if (staged)
			throw new IllegalStateException("stage() called twice without an unstage() in between");

		final Path staging = stagingDir(projectRoot);
		Files.createDirectories(staging);
		staged = true; // from here on, unstage() must run even if a step below throws

		stageOne(".project", projectXml, staging);
		stageOne(".classpath", classpathXml, staging);
	}

	private void stageOne(final String name, final String content, final Path staging) throws IOException {
		final Path live = projectRoot.resolve(name);
		if (Files.exists(live))
			Files.move(live, staging.resolve(name), StandardCopyOption.ATOMIC_MOVE);

		Files.writeString(live, content, StandardCharsets.UTF_8);
		Files.writeString(staging.resolve(name + DEBUG_SUFFIX), content, StandardCharsets.UTF_8);
	}

	/**
	 * Puts .project/.classpath back the way they were before stage() ran - or
	 * removes clide's own file if there was nothing to restore. Safe to call
	 * even when stage() never ran (a no-op), so a caller does not need to track
	 * whether staging actually happened before deciding to clean up.
	 */
	public void unstage() throws IOException {
		if (staged == false)
			return;

		final Path staging = stagingDir(projectRoot);
		unstageOne(".project", staging);
		unstageOne(".classpath", staging);
		staged = false;
	}

	private void unstageOne(final String name, final Path staging) throws IOException {
		final Path live = projectRoot.resolve(name);
		final Path original = staging.resolve(name);

		if (Files.exists(original))
			// One atomic replace rather than delete-then-move: a crash between the two
			// would otherwise leave neither file in place.
			Files.move(original, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		else
			Files.deleteIfExists(live);

		// The debug copy (<name>.clide) is left alone - see the class doc.
	}

}
