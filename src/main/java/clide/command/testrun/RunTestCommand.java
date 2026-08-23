package clide.command.testrun;

import java.io.IOException;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.PositionParser;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;
import clide.model.Position;
import clide.test.ProjectTests;
import clide.test.TestSelector;

/**
 * Runs one test of the open project - the second priority this project set
 * itself (see CLAUDE.md), after "does it compile".
 *
 * Takes a position rather than a fully qualified class name so that the
 * answer of find_symbol can be pasted in with no editing, the chaining
 * TESTS.md keeps identifying as the tool's strength:
 *
 * <pre>
 * find_symbol shouldRenderArrow
 *   -&gt; [method] src/test/java/.../ArrowTest.java:88:14:shouldRenderArrow void shouldRenderArrow()
 * run_test src/test/java/.../ArrowTest.java:88:14:shouldRenderArrow
 * </pre>
 */
public class RunTestCommand extends Command {

	@Keyword("run_test")
	@Help("Runs the unit test <position> points at: the whole class when it names the test class, that one method otherwise.")
	@Param(type = ParamType.POSITION, description = "Test position")
	@Manual("""
			NAME
				run_test - run one unit test of the open project

			SYNOPSIS
				run_test <position>

			DESCRIPTION
				Runs the test <position> designates and reports every test
				that ran, failures first-class. The class is read off the
				file <position> points into - its package declaration plus
				its own name - and the granularity comes from what
				<position> names: the class itself runs all of its tests,
				anything else runs that single method.

				No build tool is involved. jdtls already knows the project's
				test classpath, so clide forks a JVM on it plus its own jar,
				which carries the JUnit platform and both engines - Jupiter
				for JUnit 5, Vintage for JUnit 3 and 4. A project shipping
				its own JUnit keeps it: clide's jar goes last on the
				classpath and only fills in what is missing.

				Each failure is reported as "path:line" plus the test's
				name - a stack frame carries no column, so this is one
				notch short of a full <position>: add the column of the
				name on that line to feed it back into hover or
				find_reference. When the exception came from somewhere
				other than the test's own line, that place is named too.

			ERRORS
				run_test does NOT recompile first - it reports the state of
				the last build. Run rebuild after editing, or the answer
				describes code that no longer exists.

				Finding no test at all is an error, not an empty success: an
				empty run is far more often a wrong position or a missing
				rebuild than a class with no tests. A run exceeding 120
				seconds is killed and reported as a timeout, which is not a
				test failure. A repository holding several modules is
				refused, with the modules listed - clide cannot yet be told
				which one to test.

				When <position> carries a <file-content-md5>, that
				signature must still be the file's own: a file edited
				since the position was produced is refused
				(FILE_MODIFIED) rather than answered about. The md5 is
				optional on input - a <position> written without it
				means "against the file currently on disk" - but clide
				always prints one, so a result pasted straight back in
				carries the check with it.

			SEE ALSO
				run_tests(1), rebuild(1), find_symbol(1)
			""")
	public RunTestCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), session, params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "run_test failed: " + e.getMessage());
		}

		final String[] selector;
		try {
			selector = TestSelector.forFile(position.fileIn(context.getProjectRoot()), position.name());
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.FILE_UNREADABLE,
					"could not read " + position.path() + ": " + e.getMessage());
		}

		// selector[1] is the class either way; selector[2], when present, is the
		// method/@Nested name inside it - see TestSelector.selector(). Rebuilt here
		// rather than read straight off selector[1] so "no test found in ..." still
		// names the full target, not just the class it was searched in.
		final String what = selector.length > 2 ? selector[1] + "#" + selector[2] : selector[1];
		return ProjectTests.runSelection(context, selector, what);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return TestRunRendering.render("run_test", result);
	}

}
