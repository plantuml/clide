package clide.command.testrun;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;

/** Chooses whether a profile counts the project's test code as the project's own. */
public class SetProfileScopeCommand extends Command {

	@Keyword("set_profile_scope")
	@Help("Chooses what profile_test, profile_tests and profile_report call the project's code: <main> for the production sources only, <all> to count the test sources too, for this session only.")
	@Param(type = ParamType.SINGLE_LINE, description = "Scope: main or all")
	@Manual("""
			NAME
				set_profile_scope - production code only, or tests too

			SYNOPSIS
				set_profile_scope <scope>

			DESCRIPTION
				A profile attributes time and allocation to "the project's
				code": a frame counts when its class has a .java file under one
				of the source folders. With "main", the default, those are the
				production folders (src/main/java...). Test code is then folded
				into what it calls, like a library: a regression suite spends a
				good part of its time in its own harness - normalising an output
				before comparing it, say - and left in scope that harness would
				head every table and hide the code under test.

				"all" counts the test sources too: the right choice to profile
				the tests themselves, or a test helper.

				It applies to the next profile_report as well as to the next
				profile_test, since a recording is read through the scope that is
				current when it is asked for. Prints the previous scope and the
				new one. reset_test_settings puts it back to "main", and so does
				the end of the session.

			ERRORS
				<scope> must be exactly "main" or "all" (INVALID_ENUM_VALUE).

			SEE ALSO
				profile_test(1), profile_report(1), reset_test_settings(1)
			""")
	public SetProfileScopeCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("scope", params[0], "main", "all");
		if (rejected != null)
			return rejected;

		final String previous = context.isProfileIncludingTests() ? "all" : "main";
		context.setProfileIncludingTests(params[0].equals("all"));
		return CommandResult.ok(new CommandPayload.Setting("profile_scope", previous, params[0]));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Setting setting ->
			"set_profile_scope: " + setting.name() + " " + setting.previousValue() + " -> " + setting.newValue();
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
