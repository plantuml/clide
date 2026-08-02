package clide.jdtls;

import java.io.BufferedInputStream;
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

	/**
	 * Name of the vendored jdtls archive committed next to jdtlsHome (its parent
	 * directory), used to bootstrap jdtlsHome the first time it's needed - see
	 * ensureExtracted(). Produced by scripts/download_and_zip_jdtls.py.
	 */
	private static final String JDTLS_ZIP_NAME = "jdt-language-server-latest.zip";

	/**
	 * Classpath location of the same archive when it's bundled inside the jar
	 * instead of vendored on disk - see "ant dist-with-jdtls" in build.xml,
	 * which packs it under a top-level resource/ directory. Falling back to
	 * this in ensureExtracted() lets the fat jar bootstrap jdtlsHome entirely
	 * on its own, with no zip needing to sit next to it on disk.
	 */
	private static final String JDTLS_ZIP_RESOURCE = "/resource/" + JDTLS_ZIP_NAME;

	private final Path jdtlsHome;
	private Process process;

	public JdtlsLauncher(final Path jdtlsHome) {
		this.jdtlsHome = jdtlsHome;
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
		final Path dataDir = Files.createTempDirectory("clide-jdtls-data");

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
	 * Sends the process a polite SIGTERM, then waits for it to actually exit
	 * before returning - not fire-and-forget. jdtls runs its own graceful
	 * shutdown on that signal (on top of the LSP "shutdown"/"exit" handshake
	 * JdtlsSession.stop() already ran before calling this), which can include
	 * writing .project back on its own (see EclipseProjectFiles' class doc) -
	 * callers that clean up after that write (ClideDaemon.shutdown()) need the
	 * process, and whatever it does on the way out, to be fully done first.
	 * Force-kills if it hasn't exited within the timeout, so a stuck process
	 * never wedges daemon shutdown.
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
	 * Bootstraps jdtlsHome from the vendored zip the first time it's needed - a
	 * fresh clone only has the zip committed, not the extracted jdtls tree (which
	 * is .gitignore'd). No-op once jdtlsHome/plugins already exists, so this costs
	 * a single directory check on every normal start() call.
	 *
	 * Extracts into a sibling temp directory first, then moves it into place with
	 * an atomic rename. This makes concurrent bootstraps safe: if two daemons (for
	 * two different projects) both start on the same fresh clone at once, both may
	 * extract into their own temp directory, but only one rename can win - the
	 * loser detects that jdtlsHome/plugins now exists (the winner got there first),
	 * discards its own now-redundant temp directory, and moves on as if it had
	 * found jdtlsHome ready from the start.
	 *
	 * The archive itself is looked for in two places, in order: JDTLS_ZIP_NAME
	 * next to jdtlsHome on disk (the plain-checkout / "ant dist" layout), then
	 * JDTLS_ZIP_RESOURCE on the classpath (the "ant dist-with-jdtls" fat-jar
	 * layout, where it's bundled inside clide.jar itself). This makes a jar
	 * built with dist-with-jdtls fully self-sufficient - it needs nothing
	 * sitting next to it on disk.
	 */
	private void ensureExtracted() throws IOException {
		if (Files.isDirectory(jdtlsHome.resolve("plugins")))
			return;

		final Path absoluteHome = jdtlsHome.toAbsolutePath();
		final Path zip = absoluteHome.resolveSibling(JDTLS_ZIP_NAME);

		Files.createDirectories(absoluteHome.getParent());
		final Path tempDir = Files.createTempDirectory(absoluteHome.getParent(), "jdtls-extract-");
		try {
			if (Files.isRegularFile(zip)) {
				extractZip(zip, tempDir);
			} else {
				extractZipFromClasspath(tempDir);
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
	 * Extracts every entry of the on-disk `zip` under `destination`, creating
	 * directories as needed.
	 */
	private static void extractZip(final Path zip, final Path destination) throws IOException {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(zip))) {
			extractZip(in, destination);
		}
	}

	/**
	 * Same as above, but reading JDTLS_ZIP_RESOURCE from the classpath instead
	 * of a file on disk - see the fallback in ensureExtracted().
	 */
	private static void extractZipFromClasspath(final Path destination) throws IOException {
		try (InputStream in = JdtlsLauncher.class.getResourceAsStream(JDTLS_ZIP_RESOURCE)) {
			if (in == null)
				throw new IOException("jdtls is not installed: found neither " + JDTLS_ZIP_NAME
						+ " next to jdtlsHome nor the bundled " + JDTLS_ZIP_RESOURCE + " classpath resource");

			extractZip(new BufferedInputStream(in), destination);
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
