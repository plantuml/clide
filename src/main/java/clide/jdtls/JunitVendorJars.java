package clide.jdtls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Gives a target project compile-time access to the same JUnit clide already
 * carries for running its tests (see JdtlsSession, run_test/run_tests in
 * CLAUDE.md) - without that project ever committing a copy of those jars
 * itself.
 *
 * The gap this closes: clide.jar bundles the full JUnit platform (via
 * junit-platform-console-standalone) on the classpath of the JVM it forks to
 * *run* tests, but jdtls only ever sees whatever jars a target project has
 * dropped into its own .clide/ (see JdtlsSession.detectJarLibs()). A project
 * with none of its own therefore fails to *compile* its test sources under
 * jdtls - run_test then misreports the real cause as "no test found". Copying
 * JUnit jars into every target project's .clide/ would work, but a project
 * that commits .clide/ (as PlantUML's clide branch does, deliberately, so the
 * jars clide's sandbox cannot fetch from Maven Central are checked in) would
 * then have to commit clide's own JUnit alongside them - exactly what this
 * avoids.
 *
 * ensurePresent() instead extracts VENDORED_JAR_NAMES from clide.jar itself
 * (embedded unexploded under RESOURCE_PREFIX at build time - see build.xml's
 * "junit.vendor.jars" patternset) into TARGET_DIR inside the target project,
 * idempotently, and leaves a self-contained .gitignore behind so none of it -
 * nor anything else EclipseProjectFiles.STAGING_DIR now holds (.clide.lock,
 * the staged .project/.classpath) - is ever seen by git in a target project
 * that commits .clide/. JdtlsSession.detectJarLibs() then
 * lists a project's own .clide/*.jar first, these vendored ones after: the
 * target's own choice of JUnit version wins if it already has one.
 */
final class JunitVendorJars {

	static final String RESOURCE_PREFIX = "resource/vendor-junit/";

	static final List<String> VENDORED_JAR_NAMES = List.of("junit-platform-console-standalone-1.10.1.jar",
			"junit-pioneer-2.3.0.jar", "xmlunit-core-2.12.0.jar");

	/**
	 * Lives under EclipseProjectFiles.STAGING_DIR (.clide/tmp), not a directory
	 * of its own, so it is covered by the very same "keep the project root -
	 * and everything clide did not find there already - out of git" convention,
	 * rather than needing a second one.
	 */
	static final String TARGET_DIR = EclipseProjectFiles.STAGING_DIR + "/jar-junit";

	private JunitVendorJars() {
	}

	static List<Path> ensurePresent(final Path projectRoot) throws IOException {
		return ensurePresent(projectRoot, JunitVendorJars.class.getClassLoader()::getResourceAsStream);
	}

	static List<Path> ensurePresent(final Path projectRoot, final Function<String, InputStream> resourceOpener)
			throws IOException {
		final Path targetDir = projectRoot.resolve(TARGET_DIR);
		final List<Path> present = new ArrayList<>();

		for (final String jarName : VENDORED_JAR_NAMES) {
			final Path target = targetDir.resolve(jarName);
			if (Files.isRegularFile(target)) {
				present.add(target);
				continue;
			}

			final Path extracted = extract(targetDir, jarName, resourceOpener);
			if (extracted != null)
				present.add(extracted);
		}

		if (present.isEmpty() == false)
			gitignoreStagingDir(projectRoot);

		return present;
	}

	private static Path extract(final Path targetDir, final String jarName,
			final Function<String, InputStream> resourceOpener) throws IOException {
		try (InputStream in = resourceOpener.apply(RESOURCE_PREFIX + jarName)) {
			if (in == null)
				return null;

			Files.createDirectories(targetDir);
			final Path partial = targetDir.resolve(jarName + ".part");
			Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
			final Path target = targetDir.resolve(jarName);
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
			return target;
		}
	}

	private static void gitignoreStagingDir(final Path projectRoot) throws IOException {
		final Path gitignore = EclipseProjectFiles.stagingDir(projectRoot).resolve(".gitignore");
		if (Files.exists(gitignore) == false)
			Files.writeString(gitignore, "*\n", StandardCharsets.UTF_8);
	}
}
