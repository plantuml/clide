package clide.command.testrun;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.test.ProjectTests;

/** bench_test against a reference build, then against the current one. */
public class CompareTestCommand extends Command {

	@Keyword("compare_test")
	@Help("Runs bench_test twice - against the reference jars or class folders <reference>, then against the current build - and reports both medians, the delta, and for wall-clock time, CPU time and allocation separately whether the delta beats the noise.")
	@Param(type = ParamType.POSITION, description = "Test position")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Warmup iterations")
	@Param(type = ParamType.NON_NEGATIVE_INTEGER, description = "Measured iterations")
	@Param(type = ParamType.SINGLE_LINE, description = "Reference entries")
	@Manual("""
			NAME
				compare_test - is the current build slower than a reference

			SYNOPSIS
				compare_test <position> <warmup> <iterations> <reference>

			DESCRIPTION
				Runs bench_test on the test <position> designates twice, each
				in its own JVM: first with <reference> - jars or class folders,
				separated by the platform's path separator (':' or ';'),
				relative paths resolving against the project root - put in
				front of the test classpath, as set_test_classpath_prefix does,
				so that the reference's classes replace the project's own; then
				with the connection's current settings (the build jdtls
				compiled, plus a set_test_classpath_prefix if there is one).

				The answer puts the two medians side by side for wall-clock
				time, CPU time and allocation, with the delta in percent of the
				reference (negative: the current build is lower), then, for
				each of the three, its own noise and verdict. The noise is the
				smallest delta that means something: twice the standard error
				of the two medians (read off the interquartile range of each
				run, so it shrinks with the number of iterations and one spoiled
				iteration does not move it), never less than 2% for a time and
				1% for an allocation, which is counted exactly. The verdict is
				"slower" or "faster" when the delta goes beyond the noise, and
				"same" when it does not - a difference inside the noise is not
				a finding. A quiet allocation can be "faster" while the
				wall-clock time is still "same": say so, do not average them.
				When the wall-clock noise is above 10% the answer says that
				more iterations (and warmup) would narrow it.

				Assertions are disabled in both runs (-da): they are checked
				code that production does not run, and a benchmark of them
				measures the wrong program. set_test_jvm_options -ea brings
				them back.

				In Lua the same numbers are a table, which is what a guard is
				made of: "no test more than 10% slower than the reference".

				The test classes themselves come from the project in both runs;
				only the classes the reference holds are swapped. A reference
				that holds none of the classes the test exercises compares the
				build with itself, and says "same".

				The two runs share the 600-second allowance each.

			ERRORS
				Every <reference> entry must exist (FILE_NOT_FOUND), and
				bench_test's own errors apply to each run; a failure says
				which of the two it happened in.

			SEE ALSO
				bench_test(1), set_test_classpath_prefix(1), run_test(1)
			""")
	public CompareTestCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final BenchArguments arguments = BenchArguments.parse(params[1], params[2]);
		if (arguments.error != null)
			return arguments.error;

		final List<String> reference = new ArrayList<>();
		for (final String raw : params[3].split(File.pathSeparator)) {
			if (raw.isBlank())
				continue;

			final Path resolved;
			try {
				resolved = context.getProjectRoot().resolve(raw.strip()).toAbsolutePath().normalize();
			} catch (final InvalidPathException e) {
				return CommandResult.error(ErrorCode.FILE_NOT_FOUND, "'" + raw + "' is not a valid path");
			}

			if (Files.exists(resolved) == false)
				return CommandResult.error(ErrorCode.FILE_NOT_FOUND, "reference entry not found: " + resolved);

			reference.add(resolved.toString());
		}

		if (reference.isEmpty())
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, "<reference> names no jar or folder");

		final RunTestCommand.Target target = RunTestCommand.target(context, params[0], "compare_test");
		if (target.error != null)
			return target.error;

		return ProjectTests.compareSelection(context, target.selector, target.what, arguments.warmup,
				arguments.iterations, reference, params[3].strip());
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return BenchRendering.renderCompared("compare_test", result);
	}

}
