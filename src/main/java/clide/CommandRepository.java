package clide;

import java.util.ArrayList;
import java.util.List;

import clide.command.HelpCommand;
import clide.command.ManualCommand;
import clide.command.ResearchRegexCommand;
import clide.command.SetMaxResultsCommand;
import clide.command.diagnostics.PrintDiagnosticsCommand;
import clide.command.diagnostics.RebuildCommand;
import clide.command.edit.RemoveUnusedImportsCommand;
import clide.command.edit.RenameCommand;
import clide.command.navigate.FindCalleesCommand;
import clide.command.navigate.FindCallersCommand;
import clide.command.navigate.FindDeclarationCommand;
import clide.command.navigate.FindImplementationCommand;
import clide.command.navigate.FindReferenceCommand;
import clide.command.navigate.FindSubtypesCommand;
import clide.command.navigate.FindSupertypesCommand;
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
 * Every command clide registers - read by Main.main(), the daemon's one entry
 * point, and handed to ClideDaemon's constructor.
 *
 * A dedicated class with no other responsibility, kept separate from Main
 * itself: nothing about parsing CLI arguments belongs next to a static list of
 * commands.
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
		registered.add(new FindCallersCommand());
		registered.add(new FindCalleesCommand());
		registered.add(new FindSupertypesCommand());
		registered.add(new FindSubtypesCommand());
		registered.add(new RunTestCommand());
		registered.add(new RunTestsCommand());
		registered.add(new SetMaxResultsCommand());

		registered.add(new RenameCommand());
		registered.add(new RemoveUnusedImportsCommand());

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
