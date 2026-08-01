package clide.test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Entry point of the JVM clide forks to run a project's tests - never invoked
 * in clide's own process. Runs on a classpath made of the project's own
 * classpath (as jdtls reports it) followed by clide.jar, which is where this
 * class and the whole JUnit platform come from.
 *
 * Speaks a deliberately dull line protocol on stdout rather than printing
 * anything for a human: clide's process is the only reader, and it is the one
 * that turns a stack frame into a project-relative "path:line:" - it has jdtls
 * at hand for that (java.project.resolveStackTraceLocation), this JVM does not.
 * Every field is escaped, so a failure message spanning several lines stays one
 * record.
 *
 * <pre>
 * SUMMARY &lt;found&gt; &lt;succeeded&gt; &lt;failed&gt; &lt;skipped&gt; &lt;millis&gt;
 * FAIL    &lt;class&gt; &lt;method&gt; &lt;displayName&gt; &lt;message&gt; &lt;testFrame&gt; &lt;originFrame&gt;
 * PASS    &lt;class&gt; &lt;method&gt; &lt;displayName&gt;
 * SKIP    &lt;class&gt; &lt;method&gt; &lt;displayName&gt; &lt;reason&gt;
 * </pre>
 *
 * Exit codes, which are how clide tells apart the three outcomes that all look
 * alike from the outside: 0 every test passed, 1 at least one failed, 2 no test
 * was found at all, 3 the run itself broke down.
 */
public final class TestRunnerMain {

	public static final int EXIT_OK = 0;
	public static final int EXIT_FAILURES = 1;
	public static final int EXIT_NO_TEST = 2;
	public static final int EXIT_BROKEN = 3;

	public static final String SUMMARY = "SUMMARY";
	public static final String NOCLASS = "NOCLASS";
	public static final String FAIL = "FAIL";
	public static final String PASS = "PASS";
	public static final String SKIP = "SKIP";

	/**
	 * Frames from these packages are never the interesting one: they are the
	 * assertion machinery and the reflective plumbing between it and the test, not
	 * the place a reader wants to open.
	 */
	private static final List<String> UNINTERESTING_FRAMES = List.of("org.junit.", "junit.", "org.opentest4j.",
			"java.", "jdk.", "sun.", "clide.test.");

	private TestRunnerMain() {
	}

	public static void main(final String[] args) {
		final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		try {
			System.exit(run(args, out));
		} catch (final Throwable broken) {
			// Anything reaching here is clide's problem, not the project's - a
			// missing engine, an unreadable selector, a classpath that does not
			// hold together. Say so on stderr, which clide reports verbatim.
			broken.printStackTrace();
			System.exit(EXIT_BROKEN);
		}
	}

	private static int run(final String[] args, final PrintStream out) {
		final String missing = classThatIsNotThere(args);
		if (missing != null) {
			// Reported on its own rather than left to blow up inside discovery,
			// because this is the single most likely mistake: run_test does not
			// recompile, so a test written or renamed since the last build simply
			// has no .class yet. "TestEngine junit-jupiter failed to discover
			// tests" would send the reader looking in entirely the wrong place.
			out.println(String.join("\t", NOCLASS, missing));
			return EXIT_NO_TEST;
		}

		final LauncherDiscoveryRequest request = buildRequest(args);
		final Launcher launcher = LauncherFactory.create();
		final Recorder recorder = new Recorder(out);

		final long startedAt = System.currentTimeMillis();
		launcher.execute(request, recorder);
		final long elapsed = System.currentTimeMillis() - startedAt;

		out.println(String.join("\t", SUMMARY, Integer.toString(recorder.ran()), Integer.toString(recorder.succeeded),
				Integer.toString(recorder.failed), Integer.toString(recorder.skipped), Long.toString(elapsed)));

		if (recorder.ran() == 0)
			return EXIT_NO_TEST;

		return recorder.failed == 0 ? EXIT_OK : EXIT_FAILURES;
	}

	/**
	 * The class a --class/--method selector names, when it cannot be loaded off
	 * this JVM's classpath - null when there is nothing to complain about.
	 */
	private static String classThatIsNotThere(final String[] args) {
		if (args.length != 2 || args[0].equals("--scan"))
			return null;

		final int hash = args[1].indexOf('#');
		final String className = hash < 0 ? args[1] : args[1].substring(0, hash);
		try {
			Class.forName(className, false, TestRunnerMain.class.getClassLoader());
			return null;
		} catch (final ClassNotFoundException | LinkageError absent) {
			return className;
		}
	}

	private static LauncherDiscoveryRequest buildRequest(final String[] args) {
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: TestRunnerMain --class|--method|--scan <value>");

		final String value = args[1];
		return switch (args[0]) {
		case "--class" -> LauncherDiscoveryRequestBuilder.request()
				.selectors(DiscoverySelectors.selectClass(value)).build();
		case "--method" -> LauncherDiscoveryRequestBuilder.request()
				.selectors(DiscoverySelectors.selectMethod(value)).build();
		case "--scan" -> LauncherDiscoveryRequestBuilder.request()
				.selectors(DiscoverySelectors.selectClasspathRoots(Set.of(Paths.get(value)))).build();
		default -> throw new IllegalArgumentException("Unknown selector " + args[0]);
		};
	}

	/**
	 * Counts what ran and writes one record per test. Only leaves count: a
	 * container (a class, a @Nested group) finishing successfully is not a test
	 * having passed, and counting it would inflate every total.
	 */
	private static final class Recorder implements TestExecutionListener {

		private final PrintStream out;

		private TestPlan plan;
		private int succeeded;
		private int failed;
		private int skipped;

		private Recorder(final PrintStream out) {
			this.out = out;
		}

		/**
		 * How many tests actually happened. Counted as they happen, never from the
		 * plan: a @ParameterizedTest, a @RepeatedTest and a @TestFactory are
		 * CONTAINERS at plan time, their invocations being registered dynamically
		 * once execution starts. countTestIdentifiers() therefore answers 0 for a
		 * class made only of parameterized tests, however many cases it runs.
		 */
		private int ran() {
			return succeeded + failed + skipped;
		}

		@Override
		public void testPlanExecutionStarted(final TestPlan started) {
			plan = started;
		}

		@Override
		public void executionSkipped(final TestIdentifier identifier, final String reason) {
			if (identifier.isTest()) {
				recordSkip(identifier, reason);
				return;
			}

			// A skipped container - a @Disabled class - never starts its tests, so
			// nobody else will ever count them. Without this they vanish entirely
			// and the class reports as having no test at all.
			boolean any = false;
			for (final TestIdentifier descendant : plan == null ? Set.<TestIdentifier>of()
					: plan.getDescendants(identifier))
				if (descendant.isTest()) {
					recordSkip(descendant, reason);
					any = true;
				}

			// A skipped template has no invocation yet: count the template itself.
			if (any == false)
				recordSkip(identifier, reason);
		}

		private void recordSkip(final TestIdentifier identifier, final String reason) {
			skipped++;
			out.println(String.join("\t", SKIP, className(identifier), methodName(identifier),
					escape(identifier.getDisplayName()), escape(reason)));
		}

		@Override
		public void executionFinished(final TestIdentifier identifier, final TestExecutionResult result) {
			if (identifier.isTest() == false)
				return;

			if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
				succeeded++;
				out.println(String.join("\t", PASS, className(identifier), methodName(identifier),
						escape(identifier.getDisplayName())));
				return;
			}

			failed++;
			final Throwable thrown = result.getThrowable().orElse(null);
			out.println(String.join("\t", FAIL, className(identifier), methodName(identifier),
					escape(identifier.getDisplayName()), escape(describe(thrown)),
					escape(frameIn(thrown, className(identifier))), escape(originFrame(thrown))));
		}

		private String className(final TestIdentifier identifier) {
			return source(identifier, 0);
		}

		private String methodName(final TestIdentifier identifier) {
			return source(identifier, 1);
		}

		/**
		 * MethodSource is the shape a plain @Test has; anything else (a dynamic
		 * test, a container mistakenly reaching here) falls back to the unique id,
		 * which is ugly but never empty.
		 */
		private String source(final TestIdentifier identifier, final int part) {
			final Optional<org.junit.platform.engine.TestSource> testSource = identifier.getSource();
			if (testSource.isPresent()
					&& testSource.get() instanceof org.junit.platform.engine.support.descriptor.MethodSource method)
				return part == 0 ? method.getClassName() : method.getMethodName();

			if (testSource.isPresent()
					&& testSource.get() instanceof org.junit.platform.engine.support.descriptor.ClassSource type)
				return part == 0 ? type.getClassName() : "";

			return part == 0 ? identifier.getUniqueId() : "";
		}
	}

	/** "java.lang.ArithmeticException: / by zero", or just the message when it says enough. */
	private static String describe(final Throwable thrown) {
		if (thrown == null)
			return "failed with no exception";

		final String message = thrown.getMessage();
		if (message == null || message.isBlank())
			return thrown.getClass().getName();

		// An AssertionFailedError's message ("expected: <99> but was: <5>") already
		// says everything; prefixing it with its own class name is noise.
		final boolean isAssertion = thrown.getClass().getName().endsWith("AssertionFailedError")
				|| thrown instanceof AssertionError;
		return isAssertion ? message : thrown.getClass().getName() + ": " + message;
	}

	/** The frame inside the test class itself - where the assertion sits. */
	private static String frameIn(final Throwable thrown, final String className) {
		if (thrown == null)
			return "";

		for (final StackTraceElement frame : thrown.getStackTrace())
			if (frame.getClassName().equals(className) || frame.getClassName().startsWith(className + "$")
					|| frame.getClassName().startsWith(className + "."))
				return format(frame);

		return "";
	}

	/**
	 * The deepest frame that is neither JUnit nor the JDK - where the exception
	 * actually came from, which for a test that blew up inside production code is
	 * a different (and more useful) place than the test's own line.
	 */
	private static String originFrame(final Throwable thrown) {
		if (thrown == null)
			return "";

		Throwable deepest = thrown;
		while (deepest.getCause() != null && deepest.getCause() != deepest)
			deepest = deepest.getCause();

		for (final StackTraceElement frame : deepest.getStackTrace())
			if (isInteresting(frame.getClassName()))
				return format(frame);

		return "";
	}

	private static boolean isInteresting(final String className) {
		for (final String prefix : UNINTERESTING_FRAMES)
			if (className.startsWith(prefix))
				return false;

		return true;
	}

	/**
	 * Exactly the shape a stack trace prints, because that is what jdtls'
	 * java.project.resolveStackTraceLocation parses on the other side.
	 */
	private static String format(final StackTraceElement frame) {
		return "at " + frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":"
				+ frame.getLineNumber() + ")";
	}

	/** Keeps one record on one line whatever a failure message contains. */
	public static String escape(final String text) {
		if (text == null)
			return "";

		final StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			switch (c) {
			case '\\' -> out.append("\\\\");
			case '\t' -> out.append("\\t");
			case '\n' -> out.append("\\n");
			case '\r' -> out.append("\\r");
			default -> out.append(c);
			}
		}
		return out.toString();
	}

	/** Inverse of escape(), used by clide's process when reading a record. */
	public static String unescape(final String text) {
		final StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c != '\\' || i + 1 >= text.length()) {
				out.append(c);
				continue;
			}

			final char next = text.charAt(++i);
			switch (next) {
			case '\\' -> out.append('\\');
			case 't' -> out.append('\t');
			case 'n' -> out.append('\n');
			case 'r' -> out.append('\r');
			default -> out.append(next);
			}
		}
		return out.toString();
	}

	/**
	 * Where this class was loaded from - clide.jar in a normal install, the
	 * classes directory when running from a build tree. That path is what clide
	 * appends to the project's classpath so the forked JVM can find this class and
	 * the JUnit platform.
	 */
	public static Path ownCodeSource() {
		try {
			return Paths.get(TestRunnerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (final Exception unavailable) {
			return null;
		}
	}

	/** Split of one record, fields already unescaped. */
	public static List<String> parseRecord(final String line) {
		final List<String> fields = new ArrayList<>();
		for (final String field : line.split("\t", -1))
			fields.add(unescape(field));

		return fields;
	}

}
