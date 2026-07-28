package clide.jdtls;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JdtlsLauncher {

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
		if (isRunning()) {
			return;
		}

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
		if (isRunning()) {
			process.destroy();
		}
	}

	private String javaExecutable() {
		final String javaHome = System.getProperty("java.home");
		if (javaHome == null) {
			return "java";
		}
		final Path candidate = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
		return Files.isExecutable(candidate) ? candidate.toString() : "java";
	}

	private Path findEquinoxLauncher() throws IOException {
		final Path plugins = jdtlsHome.resolve("plugins");
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(plugins, "org.eclipse.equinox.launcher_*.jar")) {
			for (final Path candidate : stream) {
				return candidate;
			}
		}
		throw new IOException("Cannot find org.eclipse.equinox.launcher_*.jar in " + plugins);
	}

	private Path findSharedConfig() throws IOException {
		final String configDir;
		if (isWindows()) {
			configDir = "config_win";
		} else if (isMac()) {
			configDir = "config_mac";
		} else {
			configDir = "config_linux";
		}

		final Path candidate = jdtlsHome.resolve(configDir);
		if (Files.isDirectory(candidate) == false) {
			throw new IOException("Cannot find jdtls shared config directory " + candidate);
		}
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
