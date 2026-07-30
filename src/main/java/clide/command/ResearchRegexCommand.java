package clide.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;

public class ResearchRegexCommand extends Command {

	@Keyword("search_regex")
	@Help("Searches <initial path> for lines matching <content regex>, in files whose path matches <path regex>.")
	@Param(type = ParamType.SINGLE_LINE, description = "Initial path")
	@Param(type = ParamType.SINGLE_LINE, description = "Path regex")
	@Param(type = ParamType.SINGLE_LINE, description = "Content regex")
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
	 */
	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Path initialPath = Paths.get(params[0]).toAbsolutePath().normalize();
		if (Files.isDirectory(initialPath) == false)
			return CommandResult.error("Not a directory: " + initialPath);

		final Pattern pathPattern;
		final Pattern contentPattern;
		try {
			pathPattern = Pattern.compile(params[1]);
			contentPattern = Pattern.compile(params[2]);
		} catch (final PatternSyntaxException e) {
			return CommandResult.error("Invalid regex: " + e.getMessage());
		}

		final StringBuilder output = new StringBuilder();
		int matchingFiles = 0;
		int matchingLines = 0;
		try (Stream<Path> walk = Files.walk(initialPath)) {
			final List<Path> files = walk.filter(Files::isRegularFile).toList();
			for (final Path file : files) {
				final String normalizedPath = file.toString().replace('\\', '/');
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
						matchingLines++;
						fileHasMatch = true;
						output.append(normalizedPath).append(':').append(i + 1).append(": ").append(lines.get(i))
								.append('\n');
					}

				if (fileHasMatch)
					matchingFiles++;

			}
		} catch (final IOException e) {
			return CommandResult.error("search_regex failed: " + e.getMessage());
		}

		output.append("search_regex: ").append(matchingLines).append(" match(es) in ").append(matchingFiles)
				.append(" file(s)");
		return CommandResult.ok(output.toString().strip());
	}

}
