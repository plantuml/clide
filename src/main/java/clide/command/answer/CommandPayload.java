package clide.command.answer;

import java.util.List;

import clide.model.CodeLocation;
import clide.model.DiagnosticsReport;
import clide.model.Listing;
import clide.model.SearchMatch;
import clide.model.SymbolHit;
import clide.model.TestOutcome;
/**
 * The part of a CommandResult that varies from one command to the next - what
 * the command actually found, as data rather than as the text a client will
 * eventually read.
 *
 * <b>Sealed rather than a generic JSON value.</b> Monomorphic already models
 * "a value of a shape nobody promised", and that is the right tool at the jdtls
 * boundary, where the shape genuinely arrives from outside. It is the wrong tool
 * here: a command and the handler that renders it are both written in this
 * repository, so their agreement can be checked by the compiler instead of at
 * the first run that happens to exercise the branch. A handler that reads a
 * field the producer never wrote fails to compile; with a map keyed by strings
 * it would have failed in production, once, on the path nobody tested.
 *
 * A sealed hierarchy also makes a handler's switch exhaustive: add a payload,
 * and every switch that does not account for it stops compiling - which is how a
 * new command gets a rendering rather than silently getting none.
 *
 * The permitted records live nested here, so the whole shape of what any
 * command can answer fits on one screen. One payload per result *shape*, not per
 * command: find_declaration, find_reference and find_implementation all answer
 * with a list of locations, and share Locations rather than each getting a
 * near-identical record of its own.
 *
 * Every payload stays free of presentation: no padding, no "(s)", no line
 * breaks. Turning one into text is the job of Command.render() (see
 * CommandRendering), and doing it here would put two renderings in the codebase
 * with nothing keeping them in step.
 */
public sealed interface CommandPayload {

	/** The payload of a command with nothing to report - exit, quit, terminate. */
	record Nothing() implements CommandPayload {
	}

	/**
	 * Text clide passes through without interpreting it: a man page, or jdtls'
	 * own hover markdown. Deliberately not parsed - hover's "Source:" footer and
	 * its markdown are jdtls' business, and reformatting them here would only
	 * find new ways to mangle them.
	 */
	record Text(String text) implements CommandPayload {

		public Text {
			if (text == null)
				throw new IllegalArgumentException("text must not be null - use \"\"");
		}
	}

	/**
	 * Where a symbol is declared, used, or implemented - find_declaration,
	 * find_reference, find_implementation. subject echoes the position that was
	 * asked about, so a result reads on its own.
	 */
	record Locations(String subject, Listing<CodeLocation> locations) implements CommandPayload {
	}

	/** Symbols found by name (find_symbol) or listed on a type (list_members). */
	record Symbols(String subject, Listing<SymbolHit> symbols) implements CommandPayload {
	}

	/** The lines search_regex matched, plus how many distinct files they came from. */
	record SearchMatches(Listing<SearchMatch> matches, int fileCount) implements CommandPayload {
	}

	/** What the last build said - print_diagnostics. */
	record Diagnostics(DiagnosticsReport report) implements CommandPayload {
	}

	/**
	 * A build clide just ran - rebuild. Carries the same report as
	 * print_diagnostics plus what only rebuild knows: how many files had changed,
	 * and how long the build took.
	 *
	 * changedFiles counts what this command had to report to jdtls, not what
	 * changed since the previous build: any command questioning jdtls now
	 * resynchronises first (see ModelSync), so by the time rebuild runs, the
	 * files edited outside clide have usually already been accounted for. Zero
	 * here therefore means "jdtls was already up to date", not "nothing was
	 * edited".
	 */
	record Rebuild(int changedFiles, long elapsedMillis, DiagnosticsReport report) implements CommandPayload {
	}

	/**
	 * A test run - run_test, run_tests. The counts are of the whole run; tests is
	 * the (capped, and possibly failures-only) listing of individual outcomes, so
	 * "12 test(s), 9 passed" stays true even when only the 3 failures are listed.
	 */
	record TestRun(String subject, int passed, int failed, int skipped, long elapsedMillis,
			Listing<TestOutcome> tests, boolean failuresOnly) implements CommandPayload {

		public int total() {
			return passed + failed + skipped;
		}
	}

	/** A transaction changed state - open, commit, rollback, restore_file. */
	record Transaction(String id, Action action, String path) implements CommandPayload {

		public enum Action {
			OPENED, COMMITTED, ROLLED_BACK, FILE_RESTORED
		}

		public Transaction {
			if (id == null || id.isEmpty())
				throw new IllegalArgumentException("id must not be empty");

			if (action == null)
				throw new IllegalArgumentException("action must not be null");

			if (path == null)
				throw new IllegalArgumentException("path must not be null - use \"\" when it does not apply");
		}
	}

	/** The files a transaction has modified so far - diff_transaction with no path. */
	record ModifiedFiles(String transactionId, Listing<String> files) implements CommandPayload {
	}

	/**
	 * A unified diff of one file under one transaction - diff_transaction with a
	 * path. An empty diff means the file currently matches its pre-transaction
	 * backup, which is a fact worth reporting rather than an absence.
	 */
	record Diff(String transactionId, String path, String unifiedDiff) implements CommandPayload {
	}

	/** Every registered command - help. */
	record CommandList(Listing<CommandSummary> commands) implements CommandPayload {
	}

	/**
	 * A symbol renamed across the project - rename.
	 *
	 * <b>No occurrence count, deliberately.</b> jdtls does not answer with one
	 * TextEdit per occurrence: two occurrences on neighbouring lines come back
	 * as a single edit spanning both, whose replacement text reproduces
	 * everything in between. Counting edits would therefore report a number that
	 * looks like an occurrence count, is not one, and is wrong by an amount that
	 * varies with how the source happens to be laid out. Files are counted
	 * instead, because a file is something the answer can be checked against;
	 * find_reference on the fresh declaration below gives the occurrences, and
	 * gives them right.
	 *
	 * fileRenames is kept apart from changedFiles rather than folded into it:
	 * "7 file(s) changed" reads as a routine refactoring, "and Square.java is
	 * now Rectangle.java" is the part a reader could not have guessed.
	 *
	 * declaration is the renamed symbol's position <i>after</i> the edit, so the
	 * next command needs no find_symbol to locate what was just renamed. Null
	 * when clide could not derive one it had checked - a real possibility, and
	 * not an error (see RenameCommand.freshDeclaration()): an absent position
	 * costs a round trip, a wrong one costs an edit in the wrong place.
	 *
	 * errorCount is what the rebuild that followed the edit reported, so the
	 * answer says in one line whether the refactoring left the project
	 * compiling. The diagnostics themselves are not carried here -
	 * print_diagnostics prints them without recompiling anything.
	 */
	record Rename(String subject, String newName, Listing<String> changedFiles, List<FileRenaming> fileRenames,
			CodeLocation declaration, int errorCount) implements CommandPayload {

		/** One file that changed name because the type it declares did. */
		public record FileRenaming(String from, String to) {
		}

		public Rename {
			fileRenames = List.copyOf(fileRenames);
		}
	}

	/**
	 * Unused imports removed from one or more files - remove_unused_imports.
	 *
	 * matchedFileCount is every file &lt;path regex&gt; matched, whether or not
	 * it had an unused import to remove - "3 file(s) matched, 1 changed" is a
	 * more useful answer than staying silent about the two that were already
	 * clean. changedFiles lists only the files actually rewritten, each with
	 * the import(s) it lost, in the order they appeared in the file.
	 *
	 * errorCount is what the rebuild that followed the edit reported - the same
	 * idea as Rename.errorCount: an editing command's answer says in one line
	 * whether the project still compiles.
	 */
	record RemoveUnusedImports(int matchedFileCount, Listing<RemoveUnusedImports.FileChange> changedFiles,
			int errorCount) implements CommandPayload {

		/** One file rewritten, and the import(s) - fully-qualified, "static " kept - it lost. */
		public record FileChange(String path, List<String> removedImports) {

			public FileChange {
				removedImports = List.copyOf(removedImports);
			}
		}
	}

	/**
	 * A session setting was read or changed - set_max_results. Carrying the
	 * previous value as well as the new one is what makes the command its own
	 * read-back: the fixed arity of the line protocol leaves no room for an
	 * argument-less "show me the current value" form.
	 */
	record Setting(String name, String previousValue, String newValue) implements CommandPayload {
	}

	/** The one instance any command with nothing to report can hand back. */
	CommandPayload NOTHING = new Nothing();

}
