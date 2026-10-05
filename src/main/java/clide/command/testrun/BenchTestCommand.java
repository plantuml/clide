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
import clide.test.ProjectTests;

/**
 * Runs one test many times in one JVM and reports what the repeated runs cost -
 * see PROFILING.md, phase 2.
 */
public class BenchTestCommand extends Command {

	@Keyword("bench_test")
	@Help("Runs the unit test <position> points at <warmup> times and then <iterations> times in the same JVM, and reports the minimum, median, p90 and maximum of its time and allocation per iteration.")
	@Param(type = ParamType.POSITION, description = "Test position")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Warmup iterations")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Measured iterations")
	@Manual("""
			NAME
				bench_test - how much a test costs once the JVM is warm

			SYNOPSIS
				bench_test <position> <warmup> <iterations>

			DESCRIPTION
				Runs the test <position> designates - the method, or every
				test of the class, as run_test selects - <warmup> times, then
				<iterations> more, all in ONE JVM, and measures each of the
				<iterations>. One run of a test is mostly class loading and
				interpreted code; the repeated ones are what the JIT has made of
				the code, and the only ones a regression in the code itself shows
				up in.

				An iteration is everything the selection runs once: the test for
				a method, the sum of its tests for a class. It is measured as
				run_test measures a test - wall-clock time, CPU time and bytes
				allocated by the thread that ran it, fixtures included - and the
				answer gives, for each, the minimum, the median, the p90 and the
				maximum over the measured iterations, then the garbage
				collections they provoked, then the spread: how far the p90 is
				from the best case, in percent of the median, and how well the
				median itself is known: twice its standard error, per measure,
				which shrinks as <iterations> grows. Two runs whose medians
				differ by less than that do not differ - compare_test does
				this arithmetic for you.

				The same state is reused from one iteration to the next: a test
				that leaves something behind (a cache, a static) is measured on
				what the previous one left. Nothing forces a garbage collection
				in between.

				<warmup> is at most 1000 and may be 0; <iterations> is between
				1 and 1000. The whole benchmark must finish within 600 seconds,
				which is also what caps it.

				Assertions are disabled (-da), unlike run_test: they are checked
				code that production does not run, and measuring them measures
				the wrong program. set_test_jvm_options -ea brings them back.

				It sees the same classpath and settings as run_test:
				set_test_env, set_test_classpath_prefix and set_test_jvm_options
				apply. Like run_test it never recompiles.

			ERRORS
				A test that fails in any iteration stops the benchmark, and is
				reported as run_test reports it (TEST_FAILURES): the cost of a
				broken test is not a measure. A selection whose every test is
				skipped (a failed assumption) has nothing to measure
				(NO_TEST_FOUND). The others are run_test's.

			SEE ALSO
				compare_test(1), profile_bench(1), run_test(1)
			""")
	public BenchTestCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final BenchArguments arguments = BenchArguments.parse(params[1], params[2]);
		if (arguments.error != null)
			return arguments.error;

		final RunTestCommand.Target target = RunTestCommand.target(context, params[0], "bench_test");
		if (target.error != null)
			return target.error;

		return ProjectTests.benchSelection(context, target.selector, target.what, arguments.warmup,
				arguments.iterations);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return BenchRendering.renderBench("bench_test", result);
	}

}
