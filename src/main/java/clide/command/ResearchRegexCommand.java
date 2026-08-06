package clide.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.PositionParser;
import clide.model.Listing;
import clide.model.SearchMatch;

public class ResearchRegexCommand extends Command {

	@Keyword("search_regex")
	@Help("Searches <initial path> for lines matching <content regex>, in files whose path matches <path regex>.")
	@Param(type = ParamType.SINGLE_LINE, description = "Initial path")
	@Param(type = ParamType.REGEX, description = "Path regex")
	@Param(type = ParamType.REGEX, description = "Content regex")
	@Manual("""
			NAME
				search_regex - grep the project for lines matching a regex

			SYNOPSIS
				search_regex <initial path> <path regex> <content regex>

			DESCRIPTION
				Walks every file under <initial path>, keeps the ones whose
				path matches <path regex>, then searches each of those files
				line by line for <content regex>. Every match is printed as
				"<path>:<line>: <text>", followed by a summary line
				"search_regex: <n> match(es) in <n> file(s)". A file that
				can't be read as UTF-8 text - a binary, typically - is
				silently skipped, not reported as an error.

				<initial path> is relative to the project root, exactly like
				the <file path> half of a <position> - never to whatever
				directory the daemon was started from. An absolute path, or
				a "file:" URI, also works and is taken as-is.

				Paths are matched against <path regex>, and printed, in that
				same project-relative form with forward slashes - so the
				same <path regex> works whether clide runs on Windows or
				Linux and whoever the machine belongs to, and a result can
				be pasted straight into a <position> parameter of
				find_declaration, find_reference, find_implementation, hover
				or list_members.

				Doesn't touch jdtls: this is a plain filesystem/text search,
				not a language-server query. Use find_symbol instead when
				jdtls' own (typically fuzzy/camelCase) name matching is
				wanted rather than a literal regex over file contents.

			ERRORS
				<initial path> must resolve to an existing directory; the
				error names both what was given and what it resolved to.
				Both regexes must compile - ParamType.REGEX rejects a
				malformed one before this command ever runs.

			SEE ALSO
				find_symbol(1)
			""")
	public ResearchRegexCommand() {

	}

	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	/**
	 * Walks the initial path, keeps only files whose (forward-slash normalized)
	 * path matches the path regex, then greps the content regex line by line in
	 * those files. Path separators are normalized to '/' before matching the path
	 * regex so the same regex works whether clide runs on Windows or Linux.
	 *
	 * Both regexes are already known to compile - ParamType.REGEX (see
	 * ClideDaemon.validate()) checked that before this command ever ran - the
	 * try/catch below stays only as a defensive backstop.
	 *
	 * &lt;initial path&gt; goes through Position.resolvePath(), the same rule
	 * every other command's path follows: relative to the project root, never to
	 * the daemon's working directory. Paths are then matched and printed relative
	 * to that root as well, so &lt;path regex&gt; never has to mention a machine-
	 * specific prefix and a result can be pasted straight into a
	 * &lt;position&gt; parameter. Anything outside the project root - only
	 * reachable by passing an absolute &lt;initial path&gt; - keeps its absolute
	 * form, there being nothing to make it relative to.
	 */
	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Path projectRoot = context.getProjectRoot();
		final Path initialPath = PositionParser.resolvePath(params[0], projectRoot);
		if (Files.isDirectory(initialPath) == false)
			return CommandResult.error(ErrorCode.NOT_A_DIRECTORY,
					"Not a directory: '" + params[0] + "' (resolved against the project root " + projectRoot
							+ ", giving " + initialPath + ")");

		final Pattern pathPattern;
		final Pattern contentPattern;
		try {
			pathPattern = Pattern.compile(params[1]);
			contentPattern = Pattern.compile(params[2]);
		} catch (final PatternSyntaxException e) {
			return CommandResult.error(ErrorCode.INVALID_REGEX, "Invalid regex: " + e.getMessage());
		}

		// Every match is collected, then capped by Listing.of() below - never
		// stopped at max_results here. Walking only as far as the cap would make
		// the reported total "how far we walked", and truncated() would compare a
		// number against itself; see Listing.
		final List<SearchMatch> matches = new ArrayList<>();
		int matchingFiles = 0;
		try (Stream<Path> walk = Files.walk(initialPath)) {
			final List<Path> files = walk.filter(Files::isRegularFile).toList();
			for (final Path file : files) {
				final String normalizedPath = displayPath(file, projectRoot);
				if (pathPattern.matcher(normalizedPath).find() == false)
					continue;

				final List<String> lines;
				try {
					lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				} catch (final IOException e) {
					continue; // not text, or unreadable - skip
				}
				boolean fileHasMatch = false;
				for (int i = 0; i < lines.size(); i++)
					if (contentPattern.matcher(lines.get(i)).find()) {
						fileHasMatch = true;
						matches.add(new SearchMatch(normalizedPath, i + 1, lines.get(i)));
					}

				if (fileHasMatch)
					matchingFiles++;

			}
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "search_regex failed: " + e.getMessage());
		}

		return CommandResult.ok(
				new CommandPayload.SearchMatches(Listing.of(matches, context.getMaxResults()), matchingFiles));
	}

	/**
	 * The matched lines, then the tally. The tally goes last on purpose: it is the
	 * line a reader wants after scrolling through the matches, and it is where the
	 * truncation notice belongs when there were more matches than max_results.
	 */
	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.SearchMatches found -> {
			final Listing<SearchMatch> matches = found.matches();
			final StringBuilder out = new StringBuilder();
			for (final SearchMatch match : matches.items())
				out.append(match.display()).append('\n');

			out.append("search_regex: ").append(matches.summarize("match")).append(" in ").append(found.fileCount())
					.append(" file(s)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

	/**
	 * file as the client should see it: relative to projectRoot, forward slashes
	 * - the same shape find_symbol, find_declaration, find_reference,
	 * find_implementation, hover and list_members print, and the same shape a
	 * &lt;position&gt; parameter expects. Falls back to the absolute path for a
	 * file outside the project root.
	 */
	private String displayPath(final Path file, final Path projectRoot) {
		final Path relative = file.startsWith(projectRoot) ? projectRoot.relativize(file) : file;
		return relative.toString().replace('\\', '/');
	}

}
