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

import clide.command.result.CommandPayload;
import clide.command.result.CommandResult;
import clide.command.result.ErrorCode;
import clide.core.ClideContext;
import clide.jdtls.JdtlsLauncher;
import clide.jdtls.JdtlsSession;
import clide.result.Listing;
import clide.result.TestOutcome;

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
		final CommandResult wrongShape = onlyOneProject(context);
		if (wrongShape != null)
			return wrongShape;

		return run(context, selector, SINGLE_TIMEOUT_SECONDS, false, "run_test", what);
	}

	/** run_tests: everything discoverable in the project's test output folders. */
	public static CommandResult runEverything(final ClideContext context, final boolean failuresOnly) {
		final CommandResult wrongShape = onlyOneProject(context);
		if (wrongShape != null)
			return wrongShape;

		final JdtlsSession session = context.getCurrentSession();
		final List<String> classpath;
		try {
			classpath = session.testClasspath();
		} catch (final Exception e) {
			// No hint: whether a rebuild would fix this is not something clide knows,
			// and a guess an agent will act on costs it a 10s build and leaves it
			// exactly as stuck - see CODING.md on what a hint may claim.
			return CommandResult.error(ErrorCode.CLASSPATH_UNAVAILABLE,
					"could not read the project classpath from jdtls: " + e.getMessage());
		}

		// Scanning the whole classpath would walk every jar too - slow, and it can
		// turn up tests that are not the project's. The output folders are enough.
		final List<String> roots = outputFolders(context.getProjectRoot(), classpath);
		if (roots.isEmpty())
			return CommandResult.error(ErrorCode.NO_OUTPUT_FOLDER,
					"no compiled output folder found for this project", "run rebuild first, and check that "
							+ context.getProjectRoot().resolve(".classpath") + " declares a test source folder");

		final List<String> records = new ArrayList<>();
		long millis = 0;
		for (final String root : roots) {
			final Outcome outcome = fork(context, classpath, new String[] { "--scan", root },
					SUITE_TIMEOUT_SECONDS);
			if (outcome.failure != null)
				return CommandResult.error(outcome.failureCode, outcome.failure);

			records.addAll(outcome.records);
			millis += outcome.millis;
		}

		return report(context, "run_tests", records, millis, failuresOnly, String.join(", ", roots));
	}

	private static CommandResult run(final ClideContext context, final String[] selector, final long timeoutSeconds,
			final boolean failuresOnly, final String label, final String what) {
		final List<String> classpath;
		try {
			classpath = context.getCurrentSession().testClasspath();
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.CLASSPATH_UNAVAILABLE,
					"could not read the project classpath from jdtls: " + e.getMessage());
		}

		final Outcome outcome = fork(context, classpath, selector, timeoutSeconds);
		if (outcome.failure != null)
			return CommandResult.error(outcome.failureCode, outcome.failure);

		return report(context, label, outcome.records, outcome.millis, failuresOnly, what);
	}

	// ------------------------------------------------------------------
	// Forking
	// ------------------------------------------------------------------

	private static Outcome fork(final ClideContext context, final List<String> classpath, final String[] selector,
			final long timeoutSeconds) {
		final List<String> own = ownClasspath();
		if (own.isEmpty())
			return Outcome.broken(ErrorCode.TEST_RUNNER_BROKEN,
					"clide cannot locate its own classpath, so it cannot hand the JUnit platform to the test JVM");

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
			return Outcome.broken(ErrorCode.TEST_RUNNER_BROKEN, "could not start the test JVM: " + e.getMessage());
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
			return Outcome.broken(ErrorCode.TEST_RUNNER_BROKEN, "interrupted while running the tests");
		}

		if (finished == false) {
			process.destroyForcibly();
			return Outcome.broken(ErrorCode.TEST_TIMEOUT, "the tests did not finish within " + timeoutSeconds
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
			return Outcome.broken(ErrorCode.TEST_RUNNER_BROKEN,
					"the test JVM failed to run the tests: " + firstLine(stderr.toString()));

		return Outcome.of(lines);
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
			final long millis, final boolean failuresOnly, final String what) {
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			if (fields.get(0).equals(TestRunnerMain.NOCLASS))
				return CommandResult.error(ErrorCode.TEST_CLASS_NOT_COMPILED,
						fields.get(1) + " is not in the compiled output",
						"run rebuild first if you have just written or renamed it - " + label
								+ " never compiles anything itself");
		}

		final int[] tally = tally(records);
		final int passed = tally[0];
		final int failed = tally[1];
		final int skipped = tally[2];
		if (passed + failed + skipped == 0)
			return CommandResult.error(ErrorCode.NO_TEST_FOUND, "no test found in " + what,
					"an empty run is far more often a wrong selector or a missing rebuild than a project "
							+ "with no tests");

		final List<TestOutcome> outcomes = outcomes(context, records, failuresOnly);
		final CommandPayload payload = new CommandPayload.TestRun(what, passed, failed, skipped, millis,
				Listing.of(outcomes, context.getMaxResults()), failuresOnly);

		// A run that completed with failures is still reported as an ERROR, as it
		// always has been - "did my tests pass" is the question, and a client that
		// only looks at the status must not read a red suite as a green one. The
		// payload rides along all the same, so the failures are listed under the
		// error header rather than lost with it.
		if (failed == 0)
			return CommandResult.ok(payload);

		return CommandResult.error(ErrorCode.TEST_FAILURES, failed + " test(s) failed out of "
				+ (passed + failed + skipped), "", payload);
	}

	/**
	 * One TestOutcome per record, minus the ones failuresOnly filters out. The
	 * counts above are tallied from the full record list before this runs, so
	 * "12 test(s), 9 passed" stays a statement about the run even when only the 3
	 * failures are listed.
	 */
	private static List<TestOutcome> outcomes(final ClideContext context, final List<String> records,
			final boolean failuresOnly) {
		final Map<String, String> resolved = new HashMap<>();
		final List<TestOutcome> outcomes = new ArrayList<>();
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			final String kind = fields.get(0);
			if (kind.equals(TestRunnerMain.SUMMARY))
				continue;

			if (kind.equals(TestRunnerMain.FAIL)) {
				outcomes.add(failure(context, fields, resolved));
				continue;
			}

			if (failuresOnly)
				continue;

			if (kind.equals(TestRunnerMain.PASS))
				outcomes.add(TestOutcome.passed(name(fields)));
			else if (kind.equals(TestRunnerMain.SKIP))
				outcomes.add(TestOutcome.skipped(name(fields), fields.get(4)));
		}
		return outcomes;
	}

	/**
	 * One failure. location is "path:line" - the prefix of what every find_*
	 * command prints, one notch short of it: a stack frame carries no column, so
	 * the caller has to add it (and the name) to get a full <position>.
	 * origin is only filled in when the exception came from somewhere other than
	 * the test's own line - for a plain failed assertion the two are the same
	 * place, and repeating it would be noise.
	 */
	private static TestOutcome failure(final ClideContext context, final List<String> fields,
			final Map<String, String> resolved) {
		final String className = fields.get(1);
		final String methodName = fields.get(2);
		final String displayName = fields.get(3);
		final String message = fields.get(4);
		final String testFrame = fields.get(5);
		final String originFrame = fields.get(6);

		final String where = locate(context, testFrame, resolved);
		final String label = displayName.isEmpty() || displayName.equals(methodName + "()") ? methodName
				: methodName + " " + displayName;

		String origin = "";
		if (originFrame.isEmpty() == false && originFrame.equals(testFrame) == false) {
			final String located = locate(context, originFrame, resolved);
			origin = located.isEmpty() ? originFrame : located;
		}

		return new TestOutcome(TestOutcome.Status.FAILED, className + "." + label,
				where.isEmpty() ? className : where, List.of(message.split("\n")), origin);
	}

	/**
	 * How many tests passed, failed and were skipped - {passed, failed, skipped},
	 * counted from the records and from nothing else.
	 *
	 * The SUMMARY line carries its own counters and they are deliberately
	 * ignored: the same facts used to travel twice, as records and as counters,
	 * and a wrong counter silently threw away twenty real results - a class made
	 * only of @ParameterizedTest reported as "no test found", its failures
	 * included, because the child counted the test plan before JUnit had
	 * registered the parameterized invocations. A count must never be able to
	 * invalidate a fact.
	 */
	static int[] tally(final List<String> records) {
		final int[] counts = new int[3];
		for (final String record : records) {
			final List<String> fields = TestRunnerMain.parseRecord(record);
			switch (fields.get(0)) {
			case TestRunnerMain.PASS -> counts[0]++;
			case TestRunnerMain.FAIL -> counts[1]++;
			case TestRunnerMain.SKIP -> counts[2]++;
			default -> {
			}
			}
		}
		return counts;
	}

	/**
	 * "demo.CalcTest.addWorks", or "demo.UrlBuilderTest.parse [7] http://x" for
	 * one case of a @ParameterizedTest.
	 *
	 * The display name is only appended when it says something the method name
	 * does not - JUnit sets it to "addWorks()" for a plain @Test, but to the
	 * parameters for each invocation of a parameterized one. Without it, twenty
	 * cases of the same method print as twenty identical lines and there is no
	 * telling which one failed.
	 */
	private static String name(final List<String> fields) {
		final String className = fields.get(1);
		final String methodName = fields.get(2);
		final String displayName = fields.get(3);
		final String qualified = className + "." + methodName;
		if (displayName.isEmpty() || displayName.equals(methodName) || displayName.equals(methodName + "()"))
			return qualified;

		return qualified + " " + displayName;
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
	private static CommandResult onlyOneProject(final ClideContext context) {
		final List<String> projects;
		try {
			projects = context.getCurrentSession().projectUris();
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED,
					"could not list the project's modules: " + e.getMessage());
		}

		if (projects.size() <= 1)
			return null;

		return CommandResult.error(ErrorCode.MULTI_MODULE_PROJECT,
				"this repository holds " + projects.size() + " modules and clide cannot yet be told which one to test: "
						+ String.join(", ", projects));
	}

	private static String firstLine(final String text) {
		final int newline = text.indexOf('\n');
		final String line = newline < 0 ? text : text.substring(0, newline);
		return line.isBlank() ? "no detail on stderr" : line.strip();
	}

	/** What one forked JVM came back with. */
	private static final class Outcome {

		private final List<String> records;
		private long millis;
		private final String failure;
		private final ErrorCode failureCode;

		private Outcome(final List<String> records, final String failure, final ErrorCode failureCode) {
			this.records = records;
			this.failure = failure;
			this.failureCode = failureCode;
		}

		private static Outcome broken(final ErrorCode failureCode, final String failure) {
			return new Outcome(List.of(), failure, failureCode);
		}

		/** SUMMARY is read for the elapsed time only - the counts come from the records. */
		private static Outcome of(final List<String> lines) {
			final Outcome outcome = new Outcome(new ArrayList<>(lines), null, ErrorCode.NONE);
			for (final String line : lines) {
				final List<String> fields = TestRunnerMain.parseRecord(line);
				if (fields.get(0).equals(TestRunnerMain.SUMMARY) && fields.size() >= 6)
					outcome.millis += Long.parseLong(fields.get(5));
			}
			return outcome;
		}
	}

}
