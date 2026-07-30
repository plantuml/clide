package clide.jdtls;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JdtlsLauncher {

	/**
	 * Name of the vendored jdtls archive committed next to jdtlsHome (its parent
	 * directory), used to bootstrap jdtlsHome the first time it's needed - see
	 * ensureExtracted(). Produced by scripts/download_and_zip_jdtls.py.
	 */
	private static final String JDTLS_ZIP_NAME = "jdt-language-server-latest.zip";

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

	public void stop() {
		if (isRunning())
			process.destroy();

	}

	private String javaExecutable() {
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
	 */
	private void ensureExtracted() throws IOException {
		if (Files.isDirectory(jdtlsHome.resolve("plugins")))
			return;

		final Path absoluteHome = jdtlsHome.toAbsolutePath();
		final Path zip = absoluteHome.resolveSibling(JDTLS_ZIP_NAME);
		if (Files.isRegularFile(zip) == false)
			throw new IOException("jdtls is not installed and the vendored archive is missing: " + zip);

		Files.createDirectories(absoluteHome.getParent());
		final Path tempDir = Files.createTempDirectory(absoluteHome.getParent(), "jdtls-extract-");
		try {
			extractZip(zip, tempDir);
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
	 * Extracts every entry of `zip` under `destination`, creating directories as
	 * needed.
	 */
	private static void extractZip(final Path zip, final Path destination) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
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

	private boolean isWindows() {
		return osName().contains("win");
	}

	private boolean isMac() {
		return osName().contains("mac");
	}

	private String osName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	}

}
