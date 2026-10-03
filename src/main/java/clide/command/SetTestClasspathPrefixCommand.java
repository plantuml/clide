package clide.command;

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
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;

/**
 * Puts classpath entries in front of the project's own for run_test and
 * run_tests, so that the tests run against a build other than the one jdtls
 * just compiled - typically an older jar, to see whether a test already failed
 * before a change.
 *
 * Every entry must exist. A classpath entry that does not is not an error to
 * the JVM, it is simply skipped, and the run then reports on the project's own
 * classes while the caller believes it measured the jar: the one failure this
 * command exists to prevent.
 */
public class SetTestClasspathPrefixCommand extends Command {

	@Keyword("set_test_classpath_prefix")
	@Help("Puts jars or class folders in front of the project's classpath for run_test/run_tests, for this session only - <entries> are separated by the platform's path separator.")
	@Param(type = ParamType.SINGLE_LINE, description = "Entries")
	@Manual("""
			NAME
				set_test_classpath_prefix - run the tests against another build

			SYNOPSIS
				set_test_classpath_prefix <entries>

			DESCRIPTION
				Puts <entries> - jars or class folders, separated by the
				platform's path separator (':' or ';') - in front of the
				project's own test classpath for run_test and run_tests. The
				first entry holding a class wins, so a jar listed here replaces
				the project's compiled classes of the same name: the tests then
				exercise that jar rather than what jdtls built. Relative paths
				resolve against the project root.

				It replaces any earlier prefix rather than adding to it, and
				prints the previous prefix and the new one (empty when there
				is none). reset_test_settings removes it, and so does the end
				of the session: the setting belongs to the connection, not to
				the daemon.

				Only the classes are replaced. The test classes themselves
				still come from the project, and so does anything the listed
				jar does not contain.

			ERRORS
				Every entry must exist, as a file or a folder; the first
				that does not is refused by name and nothing is changed. A
				missing entry would otherwise be skipped by the JVM without a
				word, and the run would report on the project's own classes.

			SEE ALSO
				set_test_env(1), set_test_jvm_options(1), reset_test_settings(1), run_test(1)
			""")
	public SetTestClasspathPrefixCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final List<String> entries = new ArrayList<>();
		for (final String raw : params[0].split(File.pathSeparator)) {
			if (raw.isBlank())
				continue;

			final Path resolved;
			try {
				resolved = context.getProjectRoot().resolve(raw.strip()).toAbsolutePath().normalize();
			} catch (final InvalidPathException e) {
				return CommandResult.error(ErrorCode.FILE_NOT_FOUND, "'" + raw + "' is not a valid path");
			}

			if (Files.exists(resolved) == false)
				return CommandResult.error(ErrorCode.FILE_NOT_FOUND,
						"classpath entry not found: " + resolved + " - the prefix stays as it was");

			entries.add(resolved.toString());
		}

		final List<String> previous = context.setTestClasspathPrefix(entries);
		return CommandResult.ok(new CommandPayload.Setting("test_classpath_prefix",
				String.join(File.pathSeparator, previous), String.join(File.pathSeparator, entries)));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Setting setting -> "set_test_classpath_prefix: " + setting.name() + " '"
				+ setting.previousValue() + "' -> '" + setting.newValue() + "'";
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
