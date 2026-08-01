package clide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import clide.command.CommitTransactionCommand;
import clide.command.DiffTransactionCommand;
import clide.command.ExitCommand;
import clide.command.FindDeclarationCommand;
import clide.command.FindImplementationCommand;
import clide.command.FindReferenceCommand;
import clide.command.FindSymbolCommand;
import clide.command.HelpAiCommand;
import clide.command.HelpCommand;
import clide.command.HoverCommand;
import clide.command.ListMembersCommand;
import clide.command.ManualCommand;
import clide.command.OpenTransactionCommand;
import clide.command.PrintDiagnosticsCommand;
import clide.command.QuitCommand;
import clide.command.RebuildCommand;
import clide.command.ResearchRegexCommand;
import clide.command.RestoreFileCommand;
import clide.command.RollbackTransactionCommand;
import clide.command.RunTestCommand;
import clide.command.RunTestsCommand;
import clide.command.TerminateCommand;
import clide.core.Command;
import clide.daemon.ClideClient;

/**
 * Entry point for clide's client role: "clide &lt;project path&gt;" connects to
 * the daemon already running for that project if there is one, otherwise starts
 * one in the background first - see ClideClient. jdtls itself is only ever
 * started/built once per project this way, not on every clide run - see
 * ClideDaemon, which is the daemon's own separate entry point (an internal
 * re-exec ClideClient spawns; not meant to be typed by hand).
 */
public class Main {

	public static final String VERSION = "0.0.1";

	public static PrintMode printMode = PrintMode.AI;

	public static final List<Command> commands = List.of(new HelpCommand(), new ManualCommand(), new HelpAiCommand(),
			new ExitCommand(), new QuitCommand(), new TerminateCommand(), new RebuildCommand(),
			new PrintDiagnosticsCommand(),
			new ResearchRegexCommand(), new FindSymbolCommand(), new HoverCommand(), new ListMembersCommand(),
			new FindDeclarationCommand(), new FindImplementationCommand(), new FindReferenceCommand(),
			new RunTestCommand(), new RunTestsCommand());

//			new OpenTransactionCommand(), new CommitTransactionCommand(), new RollbackTransactionCommand(),
//			new DiffTransactionCommand(), new RestoreFileCommand());

	public static void main(final String[] args) throws IOException, InterruptedException {
		final Path projectRoot = parseProjectRoot(args);
		if (projectRoot == null)
			return;

		new ClideClient(projectRoot).run();
	}

	/**
	 * Parses and validates the single "clide &lt;project path&gt;" argument shared
	 * by both of clide's entry points - this class (the client) and
	 * ClideDaemon.main() (the daemon, re-exec'd by ClideClient - see
	 * ClideClient.startDetachedDaemon()). Prints a usage/error message and returns
	 * null if args is invalid; never throws.
	 */
	public static Path parseProjectRoot(final String[] args) {
		if (args.length != 1) {
			System.out.println("Usage: clide <project path>");
			return null;
		}

		final Path projectRoot = Paths.get(args[0]).toAbsolutePath().normalize();
		if (Files.isDirectory(projectRoot) == false) {
			System.out.println("Not a directory: " + projectRoot);
			return null;
		}

		return projectRoot;
	}

}
