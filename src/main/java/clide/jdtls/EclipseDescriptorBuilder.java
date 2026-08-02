package clide.jdtls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the .project/.classpath XML content JdtlsSession hands to
 * EclipseProjectFiles.stage() - what tells jdtls which source folders to
 * import, which of them are tests, and which jars belong on the classpath.
 *
 * Pure function of a project's own layout (CONVENTIONAL_SOURCE_FOLDERS) and
 * its .clide/ jar cache: no jdtls, no LSP, nothing to open a connection to -
 * this only ever reads directory listings off disk, never writes anything
 * (EclipseProjectFiles.stage() still owns that) - see
 * EclipseDescriptorBuilderTest.
 *
 * Split out of JdtlsSession as the first, lowest-risk piece of the refactor
 * discussed in the JAVALENSE.md ideas thread: source-folder/classpath
 * generation shares no state and no behavior with the LSP session itself.
 */
public final class EclipseDescriptorBuilder {

	private static final List<String> CONVENTIONAL_SOURCE_FOLDERS = List.of("src/main/java", "src/main/resources",
			"src/test/java", "src/test/resources");

	/**
	 * Which of CONVENTIONAL_SOURCE_FOLDERS hold tests rather than production
	 * code. JDT only knows a source folder is a test folder if the generated
	 * .classpath says so - see buildDotClasspath().
	 */
	private static final List<String> CONVENTIONAL_TEST_FOLDERS = List.of("src/test/java", "src/test/resources");

	/**
	 * Per-project jar dependency cache - see JDTLS.md. Populated by hand (or by a
	 * future clide command); clide only reads it.
	 */
	private static final String JARS_DIR = ".clide";

	private final Path projectRoot;

	private EclipseDescriptorBuilder(final Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	public static EclipseDescriptorBuilder forProject(final Path projectRoot) {
		return new EclipseDescriptorBuilder(projectRoot);
	}

	public String buildDotProject() {
		final String name = projectRoot.getFileName().toString();
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>%s</name>
					<comment></comment>
					<projects>
					</projects>
					<buildSpec>
						<buildCommand>
							<name>org.eclipse.jdt.core.javabuilder</name>
							<arguments>
							</arguments>
						</buildCommand>
					</buildSpec>
					<natures>
						<nature>org.eclipse.jdt.core.javanature</nature>
					</natures>
				</projectDescription>
				""".formatted(name);
	}

	/** Detects the project's own source folders, then builds .classpath for them. */
	public String buildDotClasspath() {
		return buildDotClasspath(detectSourceFolders());
	}

	/**
	 * Test source folders are marked test="true" and given their own output
	 * folder (bin/test, production code going to the default bin/main), as
	 * "gradlew eclipse" would. Without that attribute JDT treats test code as
	 * production code, with three consequences that all bite later:
	 * java.project.isTestFile() answers false for a file that plainly is one,
	 * java.project.getClasspaths() returns the same thing for the "test" and the
	 * "runtime" scope, and every .class lands in one output folder with no way to
	 * tell tests from the rest.
	 *
	 * The jars of .clide/ stay unmarked, hence visible to production code too:
	 * nothing here can tell a test-only dependency from a real one, and guessing
	 * wrong in that direction merely fails to flag a questionable import, where
	 * guessing wrong in the other one would break a build that was fine.
	 */
	public String buildDotClasspath(final List<String> sourceFolders) {
		final StringBuilder xml = new StringBuilder();
		xml.append("""
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
				""");
		for (final String folder : sourceFolders)
			xml.append(sourceEntry(folder));

		for (final String jar : detectJarLibs())
			xml.append("\t<classpathentry kind=\"lib\" path=\"%s\"/>\n".formatted(jar));

		xml.append("""
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
					<classpathentry kind="output" path="bin/main"/>
				</classpath>
				""");
		return xml.toString();
	}

	private String sourceEntry(final String folder) {
		// No output= on production folders: they land in the project's default
		// output, declared as bin/main below. Naming a *third* folder here would
		// declare a default nothing ever writes to, and getClasspaths() reports a
		// never-created output folder as an Eclipse workspace path ("/proj/bin/
		// default") instead of a filesystem one - a bogus entry to filter out of
		// every classpath forever after.
		if (CONVENTIONAL_TEST_FOLDERS.contains(folder) == false)
			return "\t<classpathentry kind=\"src\" path=\"%s\"/>\n".formatted(folder);

		return """
				\t<classpathentry kind="src" output="bin/test" path="%s">
				\t\t<attributes>
				\t\t\t<attribute name="test" value="true"/>
				\t\t</attributes>
				\t</classpathentry>
				""".formatted(folder);
	}

	/**
	 * Source folders are guessed heuristically by checking which of the
	 * conventional Maven/Gradle layout directories actually exist on disk - used
	 * to build the .project/.classpath content that JdtlsSession.start() hands to
	 * EclipseProjectFiles.stage(), whether or not the project already had its own
	 * (see EclipseProjectFiles).
	 *
	 * Deliberately does NOT add a Gradle classpath container: without a real Gradle
	 * import (disabled - see JdtlsSession.initializeParams()), such a container
	 * never resolves anyway, so external dependencies stay unresolved either way -
	 * this at least gets the project recognized and its own source compiled.
	 */
	public List<String> detectSourceFolders() {
		final List<String> found = new ArrayList<>();
		for (final String candidate : CONVENTIONAL_SOURCE_FOLDERS)
			if (Files.isDirectory(projectRoot.resolve(candidate)))
				found.add(candidate);

		if (found.isEmpty() && Files.isDirectory(projectRoot.resolve("src")))
			found.add("src");

		return found;
	}

	/**
	 * Jars found in <project>/.clide (flat, non-recursive) - a per-project cache
	 * populated ahead of time (e.g. with the JUnit/AssertJ/etc. jars a project's
	 * tests need), since clide's sandbox cannot reach Maven Central to resolve them
	 * itself - followed by whatever JunitVendorJars.ensurePresent() (called from
	 * JdtlsSession.start(), before this class runs) just extracted into
	 * .clide/tmp/jar-junit/: a project's own choice of JUnit wins by coming first,
	 * clide's vendored copy only fills in what a project with none of its own
	 * would otherwise be missing. Read every time a fresh .classpath is built to
	 * hand jdtls - see EclipseProjectFiles - so a jar dropped into .clide/ is
	 * picked up by the next daemon start without anyone having to delete an old
	 * .classpath by hand first.
	 */
	private List<String> detectJarLibs() {
		final List<String> jars = new ArrayList<>(jarsIn(projectRoot.resolve(JARS_DIR)));
		jars.addAll(jarsIn(projectRoot.resolve(JunitVendorJars.TARGET_DIR)));
		return jars;
	}

	private static List<String> jarsIn(final Path dir) {
		if (Files.isDirectory(dir) == false)
			return List.of();

		final List<String> jars = new ArrayList<>();
		try (Stream<Path> entries = Files.list(dir)) {
			entries.filter(p -> p.toString().endsWith(".jar")).sorted()
					.forEach(p -> jars.add(p.toAbsolutePath().toString().replace('\\', '/')));
		} catch (final IOException e) {
			// dir present but unreadable - classpath just ends up without these jars
		}
		return jars;
	}

}
