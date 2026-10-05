package clide.command.testrun;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandResult;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * run_test with a flight recording of the test JVM, summarised into what an
 * agent can act on: the tests' verdict, the headline numbers, and the hottest
 * code of the project - see PROFILING.md.
 */
public class ProfileTestCommand extends Command {

	@Keyword("profile_test")
	@Help("Runs the unit test <position> points at like run_test, recording the test JVM, and summarises where the time and the allocation went in the project's code.")
	@Param(type = ParamType.POSITION, description = "Test position")
	@Manual("""
			NAME
				profile_test - run one unit test with a profile

			SYNOPSIS
				profile_test <position>

			DESCRIPTION
				Runs the test <position> designates exactly as run_test does
				(same selection, same classpath, same set_test_env,
				set_test_classpath_prefix and set_test_jvm_options), with the
				JVM's flight recorder on: every millisecond the CPU sample of
				each thread, the allocations, the garbage collections, the
				exceptions and the time spent blocked.

				Assertions are disabled (-da), unlike run_test: they are
				checked code that production does not run, and one of them can
				be the hottest thing in the profile without being the code
				anybody ships. set_test_jvm_options -ea brings them back.

				The answer starts with the verdict of the tests, failures
				listed as run_test lists them (use run_test for the line of
				every test), then:

				  == overview   recording length, CPU samples, GC, allocation,
				                exceptions, contention
				  == cpu: attributed   the project methods that cost the most,
				                the library code they call included
				  == alloc: by project site   who allocates the most

				Rows are "<value> <share> path:line  Class.method": the path is
				relative to the project and ready for read_lines, the method is
				what find_symbol takes. Only the first ten rows of each table
				are given; profile_report asks the same recording again, other
				views and more rows, without running anything.

				Only the project's production code is "the project": its test
				sources are folded into the library code they call, like a
				library is. A regression suite spends a good part of its time in
				its own harness, which would otherwise be the first hotspot every
				time and hide the code under test. set_profile_scope all counts
				the tests too.

				Profiling slows the run by 10 to 20 percent, and one test is a
				small sample: a single run of a suite of different tests is a
				flat profile dominated by class loading and the interpreter. For
				a hotspot, profile something that does the same work many times.

				The recording is kept as .clide/tmp/profiles/profile-N.jfr under
				the project, overwritten by the next profile. A JMC or jfr can
				open it too.

			ERRORS
				The same as run_test, and the same as profile_report for a
				recording that cannot be read (PROFILE_UNAVAILABLE) - the tests'
				totals are then in the message. A run with failing tests is
				still TEST_FAILURES, the profile in the payload next to them.

			SEE ALSO
				profile_report(1), profile_tests(1), set_profile_scope(1), run_test(1)
			""")
	public ProfileTestCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		return RunTestCommand.execute(context, params[0], "profile_test", true);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return ProfileRendering.renderProfiled("profile_test", result);
	}

}
