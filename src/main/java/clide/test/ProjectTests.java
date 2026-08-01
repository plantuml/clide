package clide.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.core.ClideContext;
import clide.core.CommandResult;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;

/**
 * Runs a project's own unit tests - the ones of the project clide has open,
 * never clide's. Shared by run_test and run_tests, which differ only in what
 * they select and how long they are allowed to take.
 *
 * No build tool is involved. jdtls already knows the project's test classpath
 * (java.project.getClasspaths) because it just compiled the project, so clide
 * forks a JVM on that classpath plus its own jar - which carries the JUnit
 * platform, both engines and TestRunnerMain - and reads back the dull line
 * protocol that class prints. Gradle or Maven would mean a daemon to wake and a
 * build system to detect; this means a process start.
 *
 * clide's jar goes LAST on the classpath: a project shipping its own JUnit
 * keeps it, and clide only fills in what is missing.
 *
 * Deliberately does NOT recompile first. A test run therefore reports the state
 * of the last build, not of the files on disk - run rebuild after editing, or
 * the answer is about code that no longer exists.
 */
public final class ProjectTests {

	/** A single class rarely runs long; a loop in it should not hold the daemon. */
	public static final long SINGLE_TIMEOUT_SECONDS = 120;

	/** A whole suite legitimately takes minutes on a project the size of PlantUML. */
	public static final long SUITE_TIMEOUT_SECONDS = 600;

	/** "at demo.Calc.div(Calc.java:9)" - the line number is the part clide needs. */
	private static final Pattern FRAME = Pattern.compile("^at .*\\(([^:()]*):(\\d+)\\)$");

	private ProjectTests() {
	}

	/** run_test: every test of one class, or one single test method. */
	public static CommandResult runSelection(final ClideContext context, final String[] selector, final String what) {
		final String wrongShape = onlyOneProject(context);
		if (wrongShape != null)
			return CommandResult.error(wrongShape);

		return run(context, selector, SINGLE_TIMEOUT_SECONDS, false, "run_test", what);
	}

	/** run_tests: everything discoverable in the project's test output folders. */
	public static CommandResult runEverything(final ClideContext context, final boolean failuresOnly) {
		final String wrongShape = onlyOneProject(context);
		if (wrongShape != null)
			return CommandResult.error(wrongShape);

		final JdtlsSession session = context.getCurrentSession();
		final List<String> classpath;
		try {
			classpath = session.testClasspath();
		} catch (final Exception e) {
			return CommandResult.error("could not read the project classpath from jdtls: " + e.getMessage());
		}

		// Scanning the whole classpath would walk every jar too - slow, and it can
		// turn up tests that are not the project's. The output folders are enough.
		final List<String> roots = outputFolders(context.getProjectRoot(), classpath);
		if (roots.isEmpty())
			return CommandResult.error(
					"no compiled output folder found for this project - run rebuild first, and check that "
							+ context.getProjectRoot().resolve(".classpath") + " declares a test source folder");

		final List<String> records = new ArrayList<>();
		int exit = TestRunnerMain.EXIT_NO_TEST;
		long millis = 0;
		final int[] totals = new int[4];
		for (final String root : roots) {
			final Outcome outcome = fork(context, classpath, new String[] { "--scan", root },
					SUITE_TIMEOUT_SECONDS);
			if (outcome.failure != null)
				return CommandResult.error(outcome.failure);

			records.addAll(outcome.records);
			millis += outcome.millis;
			for (int i = 0; i < 4; i++)
				totals[i] += outcome.totals[i];

			if (outcome.exit == TestRunnerMain.EXIT_FAILURES || exit == TestRunnerMain.EXIT_NO_TEST)
				exit = outcome.exit;
		}

		return report(context, "run_tests", records, totals, millis, exit, failuresOnly, String.join(", ", roots));
	}

	private static CommandResult run(final ClideContext context, final String[] selector, final long timeoutSeconds,
			final boolean failuresOnly, final String label, final String what) {
		final List<String> classpath;
		try {
			classpath = context.getCurrentSession().testClasspath();
		} catch (final Exception e) {
			return CommandResult.error("could not read the project classpath from jdtls: " + e.getMessage());
		}

		final Outcome outcome = fork(context, classpath, selector, timeoutSeconds);
		if (outcome.failure != null)
			return CommandResult.error(outcome.failure);

		return report(context, label, outcome.records, outcome.totals, outcome.millis, outcome.exit, failuresOnly,
				what);
	}

	// ------------------------------------------------------------------
	// Forking
	// ------------------------------------------------------------------

	private static Outcome fork(final ClideContext context, final List<String> classpath, final String[] selector,
			final long timeoutSeconds) {
		final List<String> own = ownClasspath();
		if (own.isEmpty())
			return Outcome.broken("clide cannot locate its own classpath, so it cannot hand the JUnit platform "
					+ "to the test JVM");

		// Project first, clide last: a project shipping its own JUnit keeps it.
		final List<String> full = new ArrayList<>(classpath);
		for (final String entry : own)
			if (full.contains(entry) == false)
				full.add(entry);

		final List<String> command = new ArrayList<>();
		command.add(JdtlsLauncher.javaExecutable());
		command.add("-cp");
		command.add(String.join(java.io.File.pathSeparator, full));
		command.add(TestRunnerMain.class.getName());
		command.add(selector[0]);
		command.add(selector[1]);

		final Process process;
		try {
			process = new ProcessBuilder(command).directory(context.getProjectRoot().toFile()).start();
		} catch (final IOException e) {
			return Outcome.broken("could not start the test JVM: " + e.getMessage());
		}

		final StringBuilder stderr = new StringBuilder();
		final Thread draining = new Thread(() -> drain(process.getErrorStream(), stderr), "clide-test-stderr");
		draining.setDaemon(true);
		draining.start();

		final List<String> lines = new ArrayList<>();
		final Thread reading = new Thread(() -> readLines(process.getInputStream(), lines), "clide-test-stdout");
		reading.setDaemon(true);
		reading.start();

		final boolean finished;
		try {
			finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		} catch (final InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return Outcome.broken("interrupted while running the tests");
		}

		if (finished == false) {
			process.destroyForcibly();
			return Outcome.broken("the tests did not finish within " + timeoutSeconds
					+ "s and were killed - this is a timeout, not a test failure");
		}

		try {
			reading.join(5000);
			draining.join(5000);
		} catch (final InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}

		final int exit = process.exitValue();
		if (exit == TestRunnerMain.EXIT_BROKEN)
			return Outcome.broken("the test JVM failed to run the tests: " + firstLine(stderr.toString()));

		return Outcome.of(lines, exit);
	}

	/**
	 * Everything clide itself runs on. Not just the jar holding TestRunnerMain:
	 * from an installed clide.jar the two are the same thing, but from a build
	 * tree that jar is a directory of classes with no JUnit anywhere near it, and
	 * the forked JVM would start without a launcher. java.class.path is what
	 * covers both, ownCodeSource() only standing in should clide ever be launched
	 * off the module path.
	 */
	private static List<String> ownClasspath() {
		final List<String> entries = new ArrayList<>();
		final String declared = System.getProperty("java.class.path", "");
		for (final String entry : declared.split(java.io.File.pathSeparator))
			if (entry.isBlank() == false)
				entries.add(Paths.get(entry).toAbsolutePath().toString());

		if (entries.isEmpty()) {
			final Path fallback = TestRunnerMain.ownCodeSource();
			if (fallback != null)
				entries.add(fallback.toString());
		}

		return entries;
	}

	private static void readLines(final InputStream stream, final List<String> into) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null)
				into.add(line);
		} catch (final IOException closed) {
			// the process died mid-write; whatever arrived is what we report on
		}
	}

	private static void drain(final InputStream stream, final StringBuilder into) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null)
				into.append(line).append('\n');
		} catch (final IOException closed) {
			// same
		}
	}

	// ------------------------------------------------------------------
	// Reporting
	// ------------------------------------------------------------------

	private static CommandResult report(final ClideContext context, final String label, final List<String> records,
			final int[] totals, final long millis, final int exit, final boolean failuresOnly, final String what) {
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			if (fields.get(0).equals(TestRunnerMain.NOCLASS))
				return CommandResult.error(fields.get(1)
						+ " is not in the compiled output - run rebuild first if you have just written or renamed "
						+ "it, since run_test never compiles anything itself");
		}

		if (exit == TestRunnerMain.EXIT_NO_TEST)
			return CommandResult.error("no test found in " + what
					+ " - an empty run is far more often a wrong selector or a missing rebuild than a project "
					+ "with no tests");

		final StringBuilder out = new StringBuilder();
		out.append(label).append(": ").append(totals[0]).append(" test(s), ").append(totals[1]).append(" passed, ")
				.append(totals[2]).append(" failed");
		if (totals[3] > 0)
			out.append(", ").append(totals[3]).append(" skipped");

		out.append(" in ").append(millis).append(" ms");

		final Map<String, String> resolved = new HashMap<>();
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			final String kind = fields.get(0);
			if (kind.equals(TestRunnerMain.SUMMARY))
				continue;

			if (kind.equals(TestRunnerMain.FAIL)) {
				out.append('\n').append(failureLines(context, fields, resolved));
				continue;
			}

			if (failuresOnly)
				continue;

			if (kind.equals(TestRunnerMain.PASS))
				out.append("\n[passed] ").append(fields.get(1)).append('.').append(fields.get(2));
			else if (kind.equals(TestRunnerMain.SKIP))
				out.append("\n[skipped] ").append(fields.get(1)).append('.').append(fields.get(2)).append(": ")
						.append(fields.get(4));
		}

		return exit == TestRunnerMain.EXIT_OK ? CommandResult.ok(out.toString()) : CommandResult.error(out.toString());
	}

	/**
	 * One failure, in the same "path:line:" shape every find_* command prints, so
	 * the answer can be pasted straight into hover or find_reference. The origin
	 * frame is only shown when the exception came from somewhere other than the
	 * test's own line - for a plain failed assertion the two are the same place.
	 */
	private static String failureLines(final ClideContext context, final List<String> fields,
			final Map<String, String> resolved) {
		final String className = fields.get(1);
		final String methodName = fields.get(2);
		final String message = fields.get(4);
		final String testFrame = fields.get(5);
		final String originFrame = fields.get(6);

		final String where = locate(context, testFrame, resolved);
		final StringBuilder out = new StringBuilder();
		out.append("[failed] ").append(where.isEmpty() ? className : where).append(": ").append(methodName);
		for (final String line : message.split("\n"))
			out.append("\n    ").append(line);

		if (originFrame.isEmpty() == false && originFrame.equals(testFrame) == false) {
			final String origin = locate(context, originFrame, resolved);
			out.append("\n    thrown at ").append(origin.isEmpty() ? originFrame : origin);
		}

		return out.toString();
	}

	/**
	 * "src/test/java/demo/CalcTest.java:22" for a frame jdtls can place, the raw
	 * frame otherwise. Resolution goes through jdtls rather than through a
	 * package-to-path guess: an inner class, or a class in a file that does not
	 * carry its name, would defeat the guess.
	 */
	private static String locate(final ClideContext context, final String frame, final Map<String, String> cache) {
		if (frame.isEmpty())
			return "";

		return cache.computeIfAbsent(frame, key -> {
			final Matcher matcher = FRAME.matcher(key);
			if (matcher.matches() == false)
				return "";

			final String uri = context.getCurrentSession().resolveStackTraceLocation(key);
			if (uri == null || uri.isBlank())
				return "";

			try {
				final Path file = Paths.get(URI.create(uri));
				final Path root = context.getProjectRoot();
				final String path = file.startsWith(root) ? root.relativize(file).toString() : file.toString();
				return path.replace('\\', '/') + ":" + matcher.group(2);
			} catch (final RuntimeException unusable) {
				return "";
			}
		});
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * The classpath entries that are compiled output of this project - directories
	 * under the project root, as opposed to jars and to anything outside it.
	 */
	private static List<String> outputFolders(final Path projectRoot, final List<String> classpath) {
		final List<String> folders = new ArrayList<>();
		for (final String entry : classpath) {
			final Path path = Paths.get(entry);
			if (java.nio.file.Files.isDirectory(path) && path.toAbsolutePath().startsWith(projectRoot))
				folders.add(entry);
		}
		return folders;
	}

	/**
	 * null when jdtls holds exactly one java project, an explanation otherwise.
	 *
	 * A multi-module repository has several, and there is no defensible way to
	 * pick one: guessing would run the wrong module's tests and report a clean
	 * suite. Naming them and stopping is the honest answer until run_test takes a
	 * module.
	 */
	private static String onlyOneProject(final ClideContext context) {
		final List<String> projects;
		try {
			projects = context.getCurrentSession().projectUris();
		} catch (final Exception e) {
			return "could not list the project's modules: " + e.getMessage();
		}

		if (projects.size() <= 1)
			return null;

		return "this repository holds " + projects.size()
				+ " modules and clide cannot yet be told which one to test: " + String.join(", ", projects);
	}

	private static String firstLine(final String text) {
		final int newline = text.indexOf('\n');
		final String line = newline < 0 ? text : text.substring(0, newline);
		return line.isBlank() ? "no detail on stderr" : line.strip();
	}

	/** What one forked JVM came back with. */
	private static final class Outcome {

		private final List<String> records;
		private final int[] totals = new int[4];
		private long millis;
		private final int exit;
		private final String failure;

		private Outcome(final List<String> records, final int exit, final String failure) {
			this.records = records;
			this.exit = exit;
			this.failure = failure;
		}

		private static Outcome broken(final String failure) {
			return new Outcome(List.of(), TestRunnerMain.EXIT_BROKEN, failure);
		}

		private static Outcome of(final List<String> lines, final int exit) {
			final Outcome outcome = new Outcome(new ArrayList<>(lines), exit, null);
			for (final String line : lines) {
				final List<String> fields = TestRunnerMain.parseRecord(line);
				if (fields.get(0).equals(TestRunnerMain.SUMMARY) == false || fields.size() < 6)
					continue;

				for (int i = 0; i < 4; i++)
					outcome.totals[i] += Integer.parseInt(fields.get(i + 1));

				outcome.millis += Long.parseLong(fields.get(5));
			}
			return outcome;
		}
	}

}
