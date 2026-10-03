package clide.command.testrun;

import java.io.IOException;
import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.CommandResults;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.model.ProfileTable;
import clide.profile.ProfileScope;
import clide.profile.ProfileViews;
import clide.profile.Recording;

/**
 * Asks the recording of the last profile_test/profile_tests another question,
 * without running anything again.
 */
public class ProfileReportCommand extends Command {

	@Keyword("profile_report")
	@Help("Reads the last profile again: <view> is overview, hot, inclusive, self, lines, jdk, alloc, alloc_types, contention, callers or callees; <filter> narrows it (or names the method for callers/callees), * for none.")
	@Param(type = ParamType.SINGLE_LINE, description = "View")
	@Param(type = ParamType.SINGLE_LINE, description = "Filter, or * for none")
	@Manual("""
			NAME
				profile_report - another view of the last profile

			SYNOPSIS
				profile_report <view> <filter>

			DESCRIPTION
				Reads the recording of the last profile_test or profile_tests
				again - no test is run - and prints one view of it, capped at
				max_results rows. <filter> keeps the rows whose path or name
				contains it, case-insensitively; * keeps them all. The share on
				each row is taken against the whole run, not against the rows
				kept.

				Views, all attributed to the project's own code (see
				set_profile_scope for what that is):

				  overview     the headline numbers only
				  hot          the first project method on the stack of each CPU
				               sample - its own code plus the library code it calls
				  inclusive    every project method present anywhere on the stack
				  self         the samples whose top frame is project code itself
				  lines        hot, down to the line
				  jdk          the library method at the top of the stack, with
				               the project line that called it
				  alloc        sampled allocation weight, by project line
				  alloc_types  sampled allocation weight, by type
				  contention   time blocked or parked, by project line
				  callers      who calls the methods <filter> names
				  callees      whom the methods <filter> names call

				callers and callees answer "who calls TrieImpl.getOrCreate so
				often": the sampled call tree one step around a method, library
				frames folded away, each row counting the samples in which that
				call was on the stack. Their <filter> is required - it says
				which method - and may be a part of the name, "TrieImpl" for the
				class.

				Rows are "<value> <share> path:line  Class.method", the path
				relative to the project and ready for read_lines, the method
				ready for find_symbol - hover and find_callers then say what the
				method is and who else reaches it. Values are samples (one per
				millisecond of CPU), bytes or milliseconds, as the table says.

			ERRORS
				NO_PROFILE when no profile_test or profile_tests has run since the
				daemon started. PROFILE_UNAVAILABLE when the recording cannot be
				read. <view> must be one of those above (INVALID_ENUM_VALUE), and
				callers and callees refuse "*" (VALUE_OUT_OF_RANGE).

				The recording is the daemon's, not the session's: a profile taken
				by an earlier connection or script is still there, until the next
				one replaces it.

			SEE ALSO
				profile_test(1), profile_tests(1), set_profile_scope(1), read_lines(1)
			""")
	public ProfileReportCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final CommandResult rejected = CommandResults.rejectUnlessOneOf("view", params[0],
				ProfileViews.NAMES.toArray(new String[0]));
		if (rejected != null)
			return rejected;

		final Recording recording = context.getLastRecording();
		if (recording.files().isEmpty())
			return CommandResult.error(ErrorCode.NO_PROFILE, "there is no profile to read",
					"run profile_test <position> or profile_tests first");

		final ProfileScope scope = context.getProfileScope();
		final String filter = params[1].strip().isEmpty() ? ProfileViews.NO_FILTER : params[1].strip();
		try {
			final List<ProfileTable> tables = params[0].equals(ProfileViews.OVERVIEW) ? List.of()
					: List.of(recording.table(scope, params[0], filter, context.getMaxResults()));
			return CommandResult.ok(new CommandPayload.Profile(recording.overview(scope), tables));
		} catch (final IllegalArgumentException e) {
			return CommandResult.error(ErrorCode.VALUE_OUT_OF_RANGE, e.getMessage());
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.PROFILE_UNAVAILABLE,
					"the profile cannot be read: " + e.getMessage(), "run profile_test or profile_tests again");
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return ProfileRendering.renderReport("profile_report", result);
	}

}
