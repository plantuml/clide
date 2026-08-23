package clide.jdtls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <b>Restoring right after the initial build turned out not to be reliably
 * safe</b> - despite this class having once tested exactly that (editing
 * .classpath on disk under a live daemon and observing that neither a
 * passive wait nor an explicit rebuild picked up the change). On at least
 * one real project (PlantUML, whose committed .classpath names test jars
 * - mockito, individual junit-jupiter-* artifacts - that clide never
 * vendors under .clide/; see that project's own CLAUDE.md for what it
 * actually fetches there instead), putting the original .classpath back on
 * disk right after the build was later observed to make jdtls silently
 * reimport the project a second time - internally named "&lt;project&gt;
 * (2)", the unqualified name still being held by the first, correct import -
 * using that just-restored file. Where its library list does not match what
 * .clide/ actually holds, this second, uncontrolled import reports every
 * mismatch as a build-path error: exactly the kind of finding
 * print_diagnostics exists to surface for real problems, not artifacts of
 * clide's own file juggling.
 *
 * That is why unstage() is now called only once jdtls is already being shut
 * down - from ClideDaemon.shutdown(), never right after the initial build
 * any more (see ClideDaemon.run()'s own doc for the one exception: a daemon
 * that fails before ever reaching shutdown() still restores immediately, so
 * nothing is left stranded for refuseIfDirty() to trip on the next start).
 * Whatever jdtls reacts to by then no longer matters - the daemon is already
 * on its way out.
 *
 * jdtls does, separately, sometimes WRITE .project back on its own -
 * independently of anything staged here - as part of its own "invisible
 * project" bookkeeping (it was already caught doing this once before, see
 * JDTLS.md: injecting a &lt;filteredResources&gt; filter with a
 * __CREATED_BY_JAVA_LANGUAGE_SERVER__ marker), observed around the graceful
 * LSP shutdown handshake (JdtlsSession.stop()) - i.e. around the same moment
 * unstage() now itself first runs. That is why unstage() is safe, and still
 * meant, to be called again once JdtlsSession.stop() returns (see
 * ClideDaemon.shutdown()): every call knows, per managed file, whether a
 * real original was ever there to restore (hadOriginal, set once by stage()
 * and never revisited) - so a repeat call either leaves an already-restored
 * original alone, or deletes whatever is live when there never was one,
 * catching exactly this kind of late, clide-independent rewrite.
 *
 * A copy of whichever content was actually handed to jdtls this run is kept
 * at .clide/tmp/&lt;name&gt;.clide, purely for debugging - never read back by
 * unstage(), and freely overwritten by the next stage().
 */
public final class EclipseProjectFiles {

	/**
	 * clide's scratch directory for a project - also where DaemonLock's
	 * .clide.lock lives, for the same reason everything here does: keep the
	 * project root itself free of anything clide did not find there already.
	 * Public so those two classes share this one definition rather than each
	 * hard-coding ".clide/tmp" themselves.
	 */
	public static final String STAGING_DIR = ".clide/tmp";

	private static final String DEBUG_SUFFIX = ".clide";
	private static final List<String> MANAGED_FILES = List.of(".project", ".classpath");

	private final Path projectRoot;
	private boolean staged;

	/**
	 * Per managed file name, whether the project actually had its own copy right
	 * before stage() moved it aside - set once by stageOne(), read by every
	 * unstageOne() call including repeats. Deciding restore-vs-delete from this
	 * instead of "is there still something in staging" is what makes unstage()
	 * safe to call more than once - see the class doc.
	 */
	private final Map<String, Boolean> hadOriginal = new HashMap<>();

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
		final boolean existed = Files.exists(live);
		hadOriginal.put(name, existed);
		if (existed)
			Files.move(live, staging.resolve(name), StandardCopyOption.ATOMIC_MOVE);

		Files.writeString(live, content, StandardCharsets.UTF_8);
		Files.writeString(staging.resolve(name + DEBUG_SUFFIX), content, StandardCharsets.UTF_8);
	}

	/**
	 * Puts .project/.classpath back the way they were before stage() ran - or
	 * removes clide's own file if there was nothing to restore. Safe to call even
	 * when stage() never ran (a no-op), so a caller does not need to track
	 * whether staging actually happened before deciding to clean up.
	 *
	 * Also safe - and meant - to call again after an earlier unstage() already
	 * ran: it does not flip back to a no-op, because jdtls can still write
	 * .project on its own after that point (see the class doc). A repeat call
	 * leaves an already-restored original alone (hadOriginal true, nothing left
	 * in staging to move) and deletes whatever is live when there never was an
	 * original (hadOriginal false) - so it keeps cleaning up a late rewrite
	 * every time it is called, instead of only once.
	 */
	public void unstage() throws IOException {
		if (staged == false)
			return;

		final Path staging = stagingDir(projectRoot);
		unstageOne(".project", staging);
		unstageOne(".classpath", staging);
	}

	private void unstageOne(final String name, final Path staging) throws IOException {
		final Path live = projectRoot.resolve(name);
		final Path original = staging.resolve(name);

		if (Boolean.TRUE.equals(hadOriginal.get(name))) {
			if (Files.exists(original))
				// One atomic replace rather than delete-then-move: a crash between the two
				// would otherwise leave neither file in place.
				Files.move(original, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			// else: a previous unstage() call already restored it - leave it alone,
			// do not treat "nothing left in staging" as "there was never an original".
		} else
			Files.deleteIfExists(live);

		// The debug copy (<name>.clide) is left alone - see the class doc.
	}

}
