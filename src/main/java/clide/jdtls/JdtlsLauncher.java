package clide.jdtls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JdtlsLauncher {

	private final Path jdtlsHome;
	private final Path projectRoot;
	private Process process;

	public JdtlsLauncher(final Path jdtlsHome, final Path projectRoot) {
		this.jdtlsHome = jdtlsHome;
		this.projectRoot = projectRoot;
	}

	public boolean isRunning() {
		return process != null && process.isAlive();
	}

	public Process process() {
		return process;
	}

	public void start() throws IOException {
		if (isRunning())
			return;

		ensureExtracted();

		final Path launcherJar = findEquinoxLauncher();
		final Path sharedConfig = findSharedConfig();
		// A stable, per-project directory rather than a fresh temp one - see
		// JdtlsWorkspace's own doc for why a *second* start on the same project can
		// find its previous import/index state here instead of rebuilding it from
		// nothing. Files.createDirectories() where createTempDirectory() used to
		// create the (empty, random) directory for us - this one may already exist,
		// from an earlier start on this same project.
		final Path dataDir = JdtlsWorkspace.resolveFor(projectRoot);
		Files.createDirectories(dataDir);

		final List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
		command.add("-Dosgi.bundles.defaultStartLevel=4");
		command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
		command.add("-Dosgi.checkConfiguration=true");
		command.add("-Dosgi.sharedConfiguration.area=" + sharedConfig);
		command.add("-Dosgi.sharedConfiguration.area.readOnly=true");
		command.add("-Dosgi.configuration.cascaded=true");
		command.add("-Xms1G");
		command.add("--add-modules=ALL-SYSTEM");
		command.add("--add-opens");
		command.add("java.base/java.util=ALL-UNNAMED");
		command.add("--add-opens");
		command.add("java.base/java.lang=ALL-UNNAMED");
		command.add("-jar");
		command.add(launcherJar.toString());
		command.add("-data");
		command.add(dataDir.toString());

		final ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(false);
		process = builder.start();
	}

	/**
	 * Waits up to timeoutSeconds for jdtls to exit <em>on its own</em>, having
	 * already been asked to via the LSP "shutdown" request + "exit" notification
	 * (see JdtlsSession.stop(), which calls this before falling back to stop()
	 * below). "On its own" matters here: jdtls' own exit() handler
	 * (org.eclipse.jdt.ls.core.internal.handlers.JDTLanguageServer, external to
	 * this codebase - decompiled and read to confirm this) runs
	 * IWorkspace.save(true, ...) - a real, not-instant save of the whole
	 * workspace's state - before it lets the process exit cleanly. That work only
	 * starts once jdtls' own dispatch thread gets around to the "exit"
	 * notification, which is necessarily *after* JdtlsSession.stop()'s notify()
	 * call already returned - notify(), like every LSP notification, never waits
	 * for anything.
	 *
	 * Calling stop() immediately after notify()'s return - what this method
	 * exists to stop happening - sends SIGTERM into the middle of that save,
	 * every time, on every project big enough for the save to still be running
	 * when the signal arrives. The next time jdtls opens the same workspace
	 * (see JdtlsWorkspace), it finds one saved mid-write and says so: "The
	 * workspace exited with unsaved changes in the previous session; refreshing
	 * workspace to recover changes" - and pays for that recovery with a
	 * ProjectRegistryRefreshJob measured at 12-15x its cold-start cost on a
	 * PlantUML-sized project (1.5s clean vs 18-22s recovering) - the entire
	 * reason a persistent, reused workspace (JdtlsWorkspace) was not paying for
	 * itself: the time it saved on re-import was being lost right back to this.
	 *
	 * Returns true once the process has exited - on its own just now, or already
	 * had before this was even called - false if timeoutSeconds ran out first,
	 * in which case the caller still has stop() as a backstop for a jdtls that is
	 * genuinely stuck rather than merely still saving.
	 */
	public boolean awaitExit(final long timeoutSeconds) throws InterruptedException {
		if (isRunning() == false)
			return true;

		return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
	}

	/**
	 * Sends the process a polite SIGTERM, then waits for it to actually exit
	 * before returning - not fire-and-forget. Force-kills if it hasn't exited
	 * within the timeout, so a stuck process never wedges daemon shutdown.
	 *
	 * A backstop, meant to be reached only when awaitExit() above already gave
	 * jdtls' own graceful exit a real chance and it still did not take it - see
	 * awaitExit()'s own doc for why calling this any earlier defeats a
	 * persistent workspace's entire point. Still safe to call unconditionally
	 * (JdtlsSession.stop() does, every time): a process that already exited on
	 * its own makes isRunning() false and this a no-op.
	 */
	public void stop() {
		if (isRunning() == false)
			return;

		process.destroy();
		try {
			if (process.waitFor(10, TimeUnit.SECONDS) == false)
				process.destroyForcibly();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	/**
	 * The java binary of the JVM clide itself runs on, falling back to whatever
	 * "java" resolves to on PATH. Public and static because forking jdtls is no
	 * longer the only reason clide starts a JVM - see clide.test.ProjectTests,
	 * which runs a project's tests in one.
	 */
	public static String javaExecutable() {
		final String javaHome = System.getProperty("java.home");
		if (javaHome == null)
			return "java";

		final Path candidate = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
		return Files.isExecutable(candidate) ? candidate.toString() : "java";
	}

	/**
	 * Bootstraps jdtlsHome from the vendored archive the first time it's needed -
	 * a fresh clone only has the archive, not the extracted jdtls tree. No-op once
	 * jdtlsHome/plugins already exists, so this costs a single directory check on
	 * every normal start() call.
	 *
	 * Extracts into a sibling temp directory first, then moves it into place with
	 * an atomic rename. This makes concurrent bootstraps safe: if two daemons (for
	 * two different projects) both start at once, both may extract into their own
	 * temp directory, but only one rename can win - the loser detects that
	 * jdtlsHome/plugins now exists (the winner got there first), discards its own
	 * now-redundant temp directory, and moves on as if it had found jdtlsHome
	 * ready from the start. That race is no longer the rare event it was when
	 * every working directory had its own extraction: with the shared cache of
	 * JdtlsHome, two first-ever starts genuinely collide on the same path.
	 *
	 * Where the archive is found is JdtlsArchive's business, and deliberately
	 * unrelated to where it is extracted to - see JdtlsHome.
	 */
	private void ensureExtracted() throws IOException {
		if (Files.isDirectory(jdtlsHome.resolve("plugins")))
			return;

		final Path absoluteHome = jdtlsHome.toAbsolutePath();
		final JdtlsArchive archive = JdtlsArchive.locate();

		Files.createDirectories(absoluteHome.getParent());
		final Path tempDir = Files.createTempDirectory(absoluteHome.getParent(), "jdtls-extract-");
		try {
			try (InputStream in = archive.open()) {
				extractZip(in, tempDir);
			}
			try {
				Files.move(tempDir, absoluteHome, StandardCopyOption.ATOMIC_MOVE);
			} catch (final IOException raceLost) {
				// Another process finished extracting (and moved into place) first.
				// That's fine - only re-throw if jdtlsHome still isn't actually usable.
				if (Files.isDirectory(absoluteHome.resolve("plugins")) == false)
					throw raceLost;

			}
		} finally {
			deleteRecursively(tempDir);
		}
	}

	/**
	 * Extracts every entry read from `in` under `destination`, creating
	 * directories as needed. Does not close `in` - the caller owns it.
	 */
	private static void extractZip(final InputStream in, final Path destination) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				final Path target = destination.resolve(entry.getName()).normalize();
				if (target.startsWith(destination) == false)
					// Zip-slip guard: an entry name like "../../evil" must not escape destination.
					throw new IOException("Zip entry escapes destination directory: " + entry.getName());

				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					if (target.getParent() != null)
						Files.createDirectories(target.getParent());

					Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
				}
				zis.closeEntry();
			}
		}
	}

	/**
	 * Best-effort recursive delete, used to discard a leftover/losing temp
	 * extraction.
	 */
	private static void deleteRecursively(final Path path) throws IOException {
		if (Files.exists(path) == false)
			return;

		try (var stream = Files.walk(path)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (final IOException e) {
					// leftover temp files are harmless - nothing more useful to do here
				}
			});
		}
	}

	private Path findEquinoxLauncher() throws IOException {
		final Path plugins = jdtlsHome.resolve("plugins");
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(plugins, "org.eclipse.equinox.launcher_*.jar")) {
			for (final Path candidate : stream)
				return candidate;

		}
		throw new IOException("Cannot find org.eclipse.equinox.launcher_*.jar in " + plugins);
	}

	private Path findSharedConfig() throws IOException {
		final String configDir;
		if (isWindows())
			configDir = "config_win";
		else if (isMac())
			configDir = "config_mac";
		else
			configDir = "config_linux";

		final Path candidate = jdtlsHome.resolve(configDir);
		if (Files.isDirectory(candidate) == false)
			throw new IOException("Cannot find jdtls shared config directory " + candidate);

		return candidate;
	}

	private static boolean isWindows() {
		return osName().contains("win");
	}

	private static boolean isMac() {
		return osName().contains("mac");
	}

	private static String osName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	}

}
