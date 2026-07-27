package clide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandRegistry;
import clide.core.CommandResult;
import clide.core.CommandStatus;
import clide.core.ExitCommand;
import clide.core.HelpCommand;
import clide.core.OpenProjectCommand;
import clide.core.PrintDiagnosticsCommand;
import clide.core.ResearchRegex;

public class Main {

	public static final String VERSION = "0.0.1";

	public static final List<Command> commands = List.of(new HelpCommand(), new ExitCommand(), new OpenProjectCommand(),
			new PrintDiagnosticsCommand(), new ResearchRegex());

	public static void main(final String[] args) throws IOException {
		System.out.println("Welcome to clide " + VERSION);

		final CommandRegistry registry = new CommandRegistry(commands);
		final ClideContext context = new ClideContext(commands);

		final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String line;
		while ((line = reader.readLine()) != null) {
			final String keyword = line.trim();
			if (keyword.isEmpty())
				continue;

			final Command command = registry.find(keyword);
			if (command == null) {
				System.out.println("?SYNTAX ERROR");
				continue;
			}

			final String[] params = readParams(reader, command.paramSize());
			if (params == null) {
				System.out.println("?SYNTAX ERROR: missing parameter(s) for " + keyword);
				break; // stdin ended mid-command, nothing more to read
			}

			printResult(command.executeCommand(context, params));
			if (context.isExitRequested())
				break;

		}

		context.stopAllSessions(); // covers stdin closing without an explicit "exit"

	}

	/**
	 * Reads the next 'count' lines as parameters, one per line. Returns null if
	 * input ends before all of them are read.
	 */
	private static String[] readParams(final BufferedReader reader, final int count) throws IOException {
		final String[] params = new String[count];
		for (int i = 0; i < count; i++) {
			final String paramLine = reader.readLine();
			if (paramLine == null)
				return null;

			params[i] = paramLine.trim();
		}
		return params;
	}

	private static void printResult(final CommandResult result) {
		if (result.message().isEmpty())
			return;

		if (result.status() == CommandStatus.ERROR)
			System.out.println("Error: " + result.message());
		else
			System.out.println(result.message());
	}

}
