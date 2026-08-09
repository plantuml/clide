package clide;

import java.util.ArrayList;
import java.util.List;

import clide.command.HelpCommand;
import clide.command.ManualCommand;
import clide.command.ResearchRegexCommand;
import clide.command.SetMaxResultsCommand;
import clide.command.diagnostics.PrintDiagnosticsCommand;
import clide.command.diagnostics.RebuildCommand;
import clide.command.navigate.FindDeclarationCommand;
import clide.command.navigate.FindImplementationCommand;
import clide.command.navigate.FindReferenceCommand;
import clide.command.navigate.FindSymbolCommand;
import clide.command.navigate.HoverCommand;
import clide.command.navigate.ListMembersCommand;
import clide.command.session.ExitCommand;
import clide.command.session.QuitCommand;
import clide.command.session.TerminateCommand;
import clide.command.testrun.RunTestCommand;
import clide.command.testrun.RunTestsCommand;
import clide.command.transaction.CommitTransactionCommand;
import clide.command.transaction.DiffTransactionCommand;
import clide.command.transaction.ListModifiedFilesCommand;
import clide.command.transaction.OpenTransactionCommand;
import clide.command.transaction.RestoreFileCommand;
import clide.command.transaction.RollbackTransactionCommand;
import clide.core.Command;

/**
 * Every command clide registers, as one place both of clide's entry points can
 * read from: Main.main() (the client) and ClideDaemon.main() (the daemon, a
 * second, entirely separate JVM entry point that never calls Main.main() - see
 * ClideDaemon's class doc).
 *
 * Used to be a static field on Main itself - which Main.main() never actually
 * read; only ClideDaemon did. That made ClideDaemon import a class about CLI
 * argument parsing purely to reach a shared registry that had nothing to do
 * with it. A dedicated class with no other responsibility is the honest home
 * for it.
 *
 * Filled from a static initializer rather than a plain field initializer, so
 * the list is built with one add() per command - readable, easy to diff when
 * a command is added or removed - while still ending up exposed read-only:
 * registered stays a local, mutable list until it is complete, then
 * List.copyOf() below hands out an immutable one.
 *
 * Static, not an instantiated singleton: there is no state to hide behind an
 * instance and no behavior to override here, only a single immutable list
 * every consumer reads the same way - the same shape PositionParser,
 * DiagnosticsRendering, TransactionRendering, TestRunRendering,
 * PositionCommandSupport and SymbolListRendering already use for exactly this
 * kind of shared, stateless concern.
 */
public final class CommandRepository {

	public static final List<Command> commands;

	static {
		final List<Command> registered = new ArrayList<>();
		registered.add(new HelpCommand());
		registered.add(new ManualCommand());
		registered.add(new ExitCommand());
		registered.add(new QuitCommand());
		registered.add(new TerminateCommand());
		registered.add(new RebuildCommand());
		registered.add(new PrintDiagnosticsCommand());
		registered.add(new ResearchRegexCommand());
		registered.add(new FindSymbolCommand());
		registered.add(new HoverCommand());
		registered.add(new ListMembersCommand());
		registered.add(new FindDeclarationCommand());
		registered.add(new FindImplementationCommand());
		registered.add(new FindReferenceCommand());
		registered.add(new RunTestCommand());
		registered.add(new RunTestsCommand());
		registered.add(new SetMaxResultsCommand());

		registered.add(new OpenTransactionCommand());
		registered.add(new CommitTransactionCommand());
		registered.add(new RollbackTransactionCommand());
		registered.add(new ListModifiedFilesCommand());
		registered.add(new DiffTransactionCommand());
		registered.add(new RestoreFileCommand());

		commands = List.copyOf(registered);
	}

	private CommandRepository() {
	}

}
