package clide.command;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
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
	@Help("Runs every unit test of the project: <all> reports each test, <failures> only the ones that failed.")
	@Param(type = ParamType.SINGLE_LINE, description = "Filter: all or failures")
	@Manual("""
			NAME
				run_tests - run every unit test of the open project

			SYNOPSIS
				run_tests <filter>

			DESCRIPTION
				Runs every test found in the project's compiled test output,
				and reports the totals plus one entry per test. "failures"
				narrows the listing down to the tests that failed, which on
				a suite of any size is the only part worth reading; any
				other value reports everything. The totals are printed
				either way.

				Discovery scans the project's own output folders, not the
				whole classpath: a classpath scan would walk every jar and
				could report a dependency's tests as the project's.

				Everything else works as run_test describes - no build tool,
				a forked JVM on the classpath jdtls reports, Jupiter and
				Vintage engines both available, failures reported as
				"path:line: name".

			ERRORS
				run_tests does NOT recompile first - it reports the state of
				the last build. Run rebuild after editing.

				Finding no test at all is an error rather than an empty
				success. A run exceeding 600 seconds is killed and reported
				as a timeout, which is not a test failure. A repository
				holding several modules is refused, with the modules listed.

			SEE ALSO
				run_test(1), rebuild(1)
			""")
	public RunTestsCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		// Same convention as rebuild and print_diagnostics: only the exact
		// literal narrows the listing, any other value reports everything.
		return ProjectTests.runEverything(context, params[0].equals("failures"));
	}

}
