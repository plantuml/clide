package clide.command;

import java.util.ArrayList;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Sets the options of the JVM that run_test and run_tests fork - the heap size,
 * the garbage collector, GC logging, a flight recording - none of which a client
 * could reach before: the fork is a child of the daemon and takes its command
 * line from clide alone.
 *
 * The same kind of setting as set_test_classpath_prefix: per connection, replaces
 * the earlier value as a whole, reports the previous one. The option string is
 * split on whitespace, and a double-quoted stretch is one token - a path with a
 * space in it needs no other escape.
 */
public class SetTestJvmOptionsCommand extends Command {

	@Keyword("set_test_jvm_options")
	@Help("Sets the options of the JVM run_test/run_tests start (-Xmx512m, -XX:+UseSerialGC, -Xlog:gc...), for this session only - <options> are separated by spaces.")
	@Param(type = ParamType.SINGLE_LINE, description = "Options")
	@Manual("""
			NAME
				set_test_jvm_options - set the options of the test JVM

			SYNOPSIS
				set_test_jvm_options <options>

			DESCRIPTION
				Puts <options> on the command line of the JVM that run_test and
				run_tests start, after -ea and before the classpath. The line is
				split on spaces: "-Xmx256m -XX:+UseSerialGC" is two options.
				A stretch in double quotes is one token, quotes removed, so
				-Xlog:gc:file="my logs/gc.txt" survives its space.

				It covers whatever the JVM takes: the heap (-Xmx), the collector,
				GC logging (-Xlog:gc), the compiler (-Xint,
				-XX:TieredStopAtLevel=1, -XX:+PrintCompilation), system
				properties (-Dname=value), a flight recording
				(-XX:StartFlightRecording=...). Unlike JAVA_TOOL_OPTIONS through
				set_test_env, it does not replace what the daemon's environment
				already holds, and a JVM the test itself forks does not inherit it.

				It replaces any earlier setting rather than adding to it, and
				prints the previous options and the new ones - which is also the
				only way to read it back. An empty or blank <options> clears it.
				reset_test_settings clears it as well, and so does the end of the
				session: the setting belongs to the connection, not to the daemon.

				-ea is always there first, so a later -da wins over it.

			ERRORS
				Every option must start with '-': a bare word would be read by
				the JVM as the main class. -cp, -classpath and --class-path are
				refused, since clide assembles the test classpath itself -
				set_test_classpath_prefix puts entries in front of it - and so is
				-jar. An unbalanced double quote is refused. The first offender
				is named and nothing is changed.

				The options are not checked against the JVM: an unknown one makes
				the test JVM fail to start, which run_test and run_tests report
				as TEST_RUNNER_BROKEN with the JVM's own message.

			SEE ALSO
				set_test_env(1), set_test_classpath_prefix(1), reset_test_settings(1), run_test(1)
			""")
	public SetTestJvmOptionsCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final List<String> options;
		try {
			options = tokenize(params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, e.getMessage());
		}

		final List<String> previous;
		try {
			previous = context.setTestJvmOptions(options);
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, e.getMessage() + " - the options stay as they were");
		}

		return CommandResult.ok(
				new CommandPayload.Setting("test_jvm_options", String.join(" ", previous), String.join(" ", options)));
	}

	/**
	 * Splits on whitespace outside double quotes; the quotes themselves are
	 * dropped. Package-private so the splitting is tested without a context.
	 *
	 * @throws IllegalArgumentException when a quote is never closed
	 */
	static List<String> tokenize(final String line) {
		final List<String> tokens = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean inToken = false;
		for (int i = 0; i < line.length(); i++) {
			final char c = line.charAt(i);
			if (c == '"') {
				quoted = !quoted;
				inToken = true;
			} else if (Character.isWhitespace(c) && quoted == false) {
				if (inToken)
					tokens.add(current.toString());

				current.setLength(0);
				inToken = false;
			} else {
				current.append(c);
				inToken = true;
			}
		}

		if (quoted)
			throw new IllegalArgumentException("unbalanced double quote in '" + line + "'");

		if (inToken)
			tokens.add(current.toString());

		return tokens;
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Setting setting -> "set_test_jvm_options: " + setting.name() + " '"
				+ setting.previousValue() + "' -> '" + setting.newValue() + "'";
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
