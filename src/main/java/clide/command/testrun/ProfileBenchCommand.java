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

/** bench_test with a flight recording of the JVM: enough samples on warm code for a hotspot to show. */
public class ProfileBenchCommand extends Command {

	@Keyword("profile_bench")
	@Help("Like bench_test, with the JVM recorded: the benchmark's numbers, then where the time and the allocation went in the project's code.")
	@Param(type = ParamType.POSITION, description = "Test position")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Warmup iterations")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Measured iterations")
	@Manual("""
			NAME
				profile_bench - profile a test run many times

			SYNOPSIS
				profile_bench <position> <warmup> <iterations>

			DESCRIPTION
				bench_test and profile_test in one JVM. A single run of a test
				is a flat profile - class loading, the interpreter, the harness -
				whatever the code does; the same test repeated hundreds of times
				is the profile of the code once the JIT has compiled it, where a
				hotspot stands out. The answer is the benchmark as bench_test
				prints it, then the overview and the first rows of the cpu and
				alloc tables as profile_test prints them.

				The recording covers the warmup iterations too: a JFR cannot
				tell them apart. With enough iterations they are a few samples
				among thousands. The recording becomes the one profile_report
				reads, so every other view and filter is one command away.

			ERRORS
				bench_test's, and profile_test's PROFILE_UNAVAILABLE.

			SEE ALSO
				bench_test(1), profile_report(1), set_profile_scope(1)
			""")
	public ProfileBenchCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final BenchArguments arguments = BenchArguments.parse(params[1], params[2]);
		if (arguments.error != null)
			return arguments.error;

		final RunTestCommand.Target target = RunTestCommand.target(context, params[0], "profile_bench");
		if (target.error != null)
			return target.error;

		return ProjectTests.profileBenchSelection(context, target.selector, target.what, arguments.warmup,
				arguments.iterations);
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return BenchRendering.renderBenchProfiled("profile_bench", result);
	}

}
