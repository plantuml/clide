package clide.command.testrun;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.Command;
import clide.test.ProjectTests;

/**
 * Runs the whole test suite of the open project - the "is anything broken"
 * counterpart of run_test's "is this broken".
 *
 * Discovery is limited to the project's own compiled output folders rather than
 * its whole classpath: scanning the classpath would walk every jar on it, which
 * is slow and can turn up tests belonging to a dependency rather than to the
 * project.
 */
public class RunTestsCommand extends Command {

	@Keyword("run_tests")
	@Help("Runs every unit test of the project: <all> reports each test, <failures> only the ones that failed, <slowest> and <heaviest> rank them by time and by allocation.")
	@Param(type = ParamType.SINGLE_LINE, description = "Filter: all, failures, slowest or heaviest")
	@Manual("""
			NAME
				run_tests - run every unit test of the open project

			SYNOPSIS
				run_tests <filter>

			DESCRIPTION
				Runs every test found in the project's compiled test output,
				and reports the totals plus one entry per test. "failures"
				narrows the listing down to the tests that failed, which on
				a suite of any size is the only part worth reading; "all"
				reports everything. The totals are printed either way.

				"slowest" and "heaviest" rank instead of filtering: every test
				that ran, the slowest first (wall-clock time) or the one that
				allocated the most bytes first, cut at max_results like any
				listing - so the first lines are the ones to look at. Skipped
				tests are left out, having nothing to rank. Every passed and
				failed test carries what it cost in all four views: time, CPU
				time, bytes allocated and garbage collections.

				Those costs are measured in the test JVM, around the test and
				its @BeforeEach/@AfterEach. CPU time and allocation are those of
				the thread that ran the test, so work a test hands to threads
				of its own is not counted in them; the collection count and
				time are the whole JVM's, so a neighbour's garbage may be
				charged to a test. A cost the JVM cannot measure is left out
				rather than shown as zero.

				Discovery scans the project's own output folders, not the
				whole classpath: a classpath scan would walk every jar and
				could report a dependency's tests as the project's.

				Everything else works as run_test describes - no build tool,
				a forked JVM on the classpath jdtls reports, assertions
				enabled, Jupiter and Vintage engines both available, failures
				reported as "path:line: name".

			ERRORS
				<filter> must be exactly "all", "failures", "slowest" or
				"heaviest" - anything else, including a typo, is rejected
				(INVALID_ENUM_VALUE) rather than silently treated as "all".

				run_tests does NOT recompile first - it reports the state of
				the last build. Run rebuild after editing.

				Finding no test at all is an error rather than an empty
				success. A run exceeding 600 seconds is killed and reported
				as a timeout, which is not a test failure. A repository
				holding several modules is refused, with the modules listed.

			SEE ALSO
				run_test(1), rebuild(1), set_test_jvm_options(1)
			""")
	public RunTestsCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("filter", params[0], "all", "failures",
				"slowest", "heaviest");
		if (rejected != null)
			return rejected;

		return ProjectTests.runEverything(context, switch (params[0]) {
		case "failures" -> ProjectTests.View.FAILURES;
		case "slowest" -> ProjectTests.View.SLOWEST;
		case "heaviest" -> ProjectTests.View.HEAVIEST;
		default -> ProjectTests.View.ALL;
		});
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return TestRunRendering.render("run_tests", result);
	}

}
