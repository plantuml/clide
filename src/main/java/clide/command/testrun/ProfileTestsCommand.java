package clide.command.testrun;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.Command;
import clide.test.ProjectTests;

/** run_tests with a flight recording of every test JVM - see profile_test. */
public class ProfileTestsCommand extends Command {

	@Keyword("profile_tests")
	@Help("Runs every unit test of the project like run_tests, recording the test JVMs, and summarises where the time and the allocation went in the project's code.")
	@Manual("""
			NAME
				profile_tests - run every unit test of the open project with a profile

			SYNOPSIS
				profile_tests

			DESCRIPTION
				run_tests with the flight recorder on - see profile_test for
				what is recorded and how the answer reads. Only the failing
				tests are listed, as run_tests failures does; the totals are
				always given. Assertions are disabled (-da), as in profile_test.

				Discovery scans the project's test output folders, one JVM per
				folder, each writing its own recording; they are read together
				as one profile.

				A suite is not a benchmark. Hundreds of different tests on a
				cold JVM give a flat profile - nothing above a percent or two -
				made of class loading and the interpreter, and a share of the
				samples belongs to the test harness, not to the project (the
				overview says how many samples had no project frame). To find a
				precise hotspot, profile_test a test that repeats the same work.

				The run is slowed by 10 to 20 percent, which counts against the
				600 seconds a whole suite is allowed.

			ERRORS
				The same as run_tests and profile_test.

			SEE ALSO
				profile_test(1), profile_report(1), set_profile_scope(1), run_tests(1)
			""")
	public ProfileTestsCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		return ProjectTests.profileEverything(context);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return ProfileRendering.renderProfiled("profile_tests", result);
	}

}
