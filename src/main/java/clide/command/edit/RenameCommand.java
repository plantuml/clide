package clide.command.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.Md5Repository;
import clide.core.PositionParser;
import clide.edit.AppliedEdit;
import clide.edit.EditApplicationException;
import clide.edit.ResourceOperation;
import clide.edit.ResourceOperationKind;
import clide.edit.WorkspaceEdit;
import clide.jdtls.JdtlsSession;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.Position;

/**
 * The first command in clide that writes to the project: renames a symbol
 * everywhere it is really used, through jdtls' own textDocument/rename.
 *
 * <h2>One command, not one per kind of symbol</h2>
 *
 * A class, a method, a field and a local variable are all renamed by the same
 * single LSP request, and jdtls works out for itself which of them is at the
 * position it was handed. Splitting this into rename_class/rename_method/...
 * would triple the help lines, the man pages and the tests for one request,
 * and clide would have to determine the kind - an extra round trip - purely to
 * check a parameter that adds nothing: a &lt;position&gt; already names one
 * symbol and only one, by file, line, column and name. What genuinely differs
 * between the kinds is not the call but its consequences, and those belong in
 * the answer (see CommandPayload.Rename): a local variable touches one file, a
 * method propagates through the override hierarchy, a public class also
 * renames its file.
 *
 * <h2>Four things happen here, in this order, and none is optional</h2>
 *
 * <b>1. The model must still describe the project</b> - enforced upstream, by
 * CommandDispatcher, for every command declaring needsFreshModel(); this one
 * is only where the need was found. jdtls computes the edit against the
 * workspace it built last, and clide never opens documents, so nothing tells
 * it about a file edited outside. Measured, on a real jdtls:
 * <ul>
 * <li>a file jdtls <i>would</i> touch, edited since the build: jdtls refuses on
 * its own, "Resource ... is out of sync with file system". Good.</li>
 * <li>a file <i>created</i> since the build that uses the symbol: not renamed,
 * no warning, project left broken.</li>
 * <li>an existing file <i>modified</i> since the build to use the symbol: same
 * silence, same breakage - jdtls has no reason to touch a file its model says
 * has no reference, so its out-of-sync check never fires.</li>
 * </ul>
 * The last two are why the guard refuses on <i>any</i> change rather than on
 * the ones jdtls happens to catch: it is the only thing standing between a
 * rename and a silently incomplete one.
 *
 * <b>2. prepareRename, before anything is computed.</b> Also measured: on a
 * keyword, textDocument/rename answers with an <i>empty</i> WorkspaceEdit -
 * indistinguishable, at that point, from a symbol with nothing to change. On a
 * JDK method it answers an error instead. prepareRename separates "clide will
 * not rename this" from "there was nothing to rename" while it is still cheap
 * to say so.
 *
 * <b>3. The edit is applied, then the model is brought back in step.</b>
 * refreshChangedFiles() plus a build, exactly what CLAUDE.md already requires
 * of a caller after any outside edit. Not doing it here would leave the
 * project unbuilt, unchecked, and - because of step 1 - would make the very
 * next rename refuse. The error count of that build is reported;
 * print_diagnostics gives the detail without recompiling.
 *
 * <b>4. Nothing is written outside an open transaction</b>
 * (needsOpenTransaction() below). That is what makes step 3 safe to attempt at
 * all: applying a WorkspaceEdit is not atomic across files, so a failure
 * halfway leaves some files written - and rolling the transaction back undoes
 * all of them in one step.
 */
public class RenameCommand extends Command {

	/**
	 * Reserved words and literals: a Java identifier may not be one of these,
	 * and jdtls would either refuse or produce something that does not compile.
	 * Checked here so the refusal names the parameter rather than arriving as an
	 * LSP error about a request the caller cannot see.
	 */
	private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "_", "true", "false", "null");

	@Keyword("rename")
	@Help("Renames the symbol at <position> to <new name> everywhere it is really used - requires an open transaction.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Param(type = ParamType.SINGLE_LINE, description = "New name")
	@Manual("""
			NAME
				rename - rename a symbol everywhere it is really used

			SYNOPSIS
				rename <position> <new name>

			DESCRIPTION
				Renames the symbol at <position> - a class, interface,
				enum, method, field, parameter or local variable - to
				<new name>, everywhere it is actually used, and writes
				the result. jdtls resolves what kind of symbol is there;
				there is no separate command per kind.

				Semantic, not textual: an unrelated symbol of the same
				name elsewhere is left alone, a mention inside a comment
				or a javadoc is left alone, and a usage the text does
				not spell out literally is still found. This is the
				difference a search-and-replace cannot make.

				Renaming a public type also renames its file, which is
				reported on its own line. The answer then gives the
				renamed symbol's fresh <position>, already re-derived
				and checked, so the next command needs no find_symbol.

				A rebuild runs immediately after the edit, and its error
				count is part of the answer - print_diagnostics prints
				the detail without recompiling.

				Requires an open transaction: rename writes files, and a
				transaction is what undoes them. Nothing is committed
				here - commit_transaction or rollback_transaction
				decides, and diff_transaction shows any single file
				first.

			WHAT IS NOT REPORTED
				The number of occurrences. jdtls answers with edits that
				merge neighbouring occurrences into one, so any count
				derived from them would look like an occurrence count
				without being one. Files are counted instead; for the
				occurrences, run find_reference on the fresh position
				this command returns.

			ERRORS
				STALE_MODEL - a .java file changed on disk since the
				last build, so jdtls' model no longer describes the
				project. Refused rather than answered: a file created or
				modified since the build may use this symbol without
				jdtls knowing, and would keep the old name while
				everything around it changed. Run rebuild, then rename.

				NOT_RENAMEABLE - jdtls will not rename what is at
				<position>: a keyword, a literal, or a symbol coming
				from a jar rather than from the project's own sources.

				INVALID_JAVA_NAME - <new name> is not a Java identifier,
				or is a reserved word. Note that "$" is refused too,
				although Java allows it: clide's own <position> notation
				matches a name as a whole \\w word, so a symbol named
				with a "$" could never be pointed at again.

				NO_OPEN_TRANSACTION - see open_transaction.

				EDIT_NOT_APPLICABLE - the edit jdtls computed could not
				be applied to the files as they stand. Any file already
				written when this happens is undone by rolling the
				transaction back.

			SEE ALSO
				open_transaction(1), diff_transaction(1),
				rollback_transaction(1), find_reference(1), rebuild(1)
			""")
	public RenameCommand() {

	}

	/** Writes to the project - see the class doc, point 4. */
	@Override
	public boolean needsOpenTransaction() {
		return true;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		}

		final String newName = params[1].trim();
		final CommandResult badName = rejectUnlessJavaName(newName);
		if (badName != null)
			return badName;

		final JdtlsSession session = context.getCurrentSession();
		final Path projectRoot = context.getProjectRoot();

		try {
			if (session.canRename(position) == false)
				return CommandResult.error(ErrorCode.NOT_RENAMEABLE,
						"jdtls will not rename '" + position.name() + "' at " + position.path() + ":" + position.line()
								+ ":" + position.column());

			final WorkspaceEdit edit = session.rename(position, newName);
			final AppliedEdit applied = edit.applyTo(projectRoot);

			// The model has to be told what just happened, or every later answer -
			// including the next rename's staleness check - is about the project as
			// it was before this command ran.
			session.refreshChangedFiles();
			session.build();

			return CommandResult.ok(new CommandPayload.Rename(position.name(), newName,
					Listing.of(applied.changedFiles(), context.getMaxResults()), renamings(applied),
					freshDeclaration(context, position, newName, applied),
					session.diagnosticsReport(true, context.getMaxResults()).errorCount()));

		} catch (final EditApplicationException e) {
			return CommandResult.error(ErrorCode.EDIT_NOT_APPLICABLE, "rename failed: " + e.getMessage());
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "rename failed: " + e.getMessage());
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "rename failed: " + e.getMessage());
		}
	}

	/**
	 * "$" is a legal Java identifier character and is refused anyway. clide's
	 * &lt;position&gt; notation checks a name as a whole \\w word (see Position,
	 * JdtlsResponses.identifierAt()), and \\w excludes "$" - so a symbol renamed
	 * to hold one could never be named in a position again, by this command or
	 * any other. Refusing a name clide cannot subsequently point at is worth
	 * more than accepting everything javac would.
	 */
	private static CommandResult rejectUnlessJavaName(final String candidate) {
		if (candidate.isEmpty())
			return CommandResult.error(ErrorCode.INVALID_JAVA_NAME, "<new name> is empty");

		if (RESERVED.contains(candidate))
			return CommandResult.error(ErrorCode.INVALID_JAVA_NAME,
					"'" + candidate + "' is a reserved word and cannot name a symbol");

		if (isWordStart(candidate.charAt(0)) == false)
			return CommandResult.error(ErrorCode.INVALID_JAVA_NAME,
					"'" + candidate + "' does not start with a letter or an underscore");

		for (int i = 1; i < candidate.length(); i++)
			if (isWordCharacter(candidate.charAt(i)) == false)
				return CommandResult.error(ErrorCode.INVALID_JAVA_NAME, "'" + candidate + "' holds '"
						+ candidate.charAt(i) + "', which clide's <position> notation could never name again");

		return null;
	}

	private static boolean isWordStart(final char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
	}

	private static boolean isWordCharacter(final char c) {
		return isWordStart(c) || (c >= '0' && c <= '9');
	}

	private static List<CommandPayload.Rename.FileRenaming> renamings(final AppliedEdit applied) {
		final List<CommandPayload.Rename.FileRenaming> renamings = new ArrayList<>();
		for (final ResourceOperation operation : applied.resourceOperations())
			if (operation.kind() == ResourceOperationKind.RENAME)
				renamings.add(new CommandPayload.Rename.FileRenaming(operation.path(), operation.newPath()));

		return renamings;
	}

	/**
	 * Where the renamed symbol now stands - derived, then checked, never
	 * guessed.
	 *
	 * Derived, because clide is the one that just wrote the file and does not
	 * need to ask: the declaration stays on its own line (a rename replaces
	 * identifiers, it does not add or remove lines), and the file is the old one
	 * unless this very edit renamed it. Checked, because that reasoning is not a
	 * proof: the candidate token is handed to PositionParser.parse(), the same
	 * validator every incoming position goes through, so what comes back has had
	 * its name verified as a whole word at that exact column of that exact line,
	 * against the file as it now stands.
	 *
	 * Returns null rather than a best effort whenever anything is not clear-cut
	 * - the line holds the new name more than once, or none, or the token does
	 * not validate. A missing position costs the caller one find_symbol; a wrong
	 * one costs it an edit somewhere it never meant to touch.
	 */
	private static CodeLocation freshDeclaration(final ClideContext context, final Position before,
			final String newName, final AppliedEdit applied) {
		try {
			final String path = pathAfter(before.path(), applied);
			final Path file = context.getProjectRoot().resolve(path);
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (before.line() > lines.size())
				return null;

			final String line = lines.get(before.line() - 1);
			final int column = soleWholeWordColumn(line, newName);
			if (column == -1)
				return null;

			final String token = Position.notation(Position.abbreviate(Md5Repository.md5Of(file)), path, before.line(),
					column, newName);
			return new CodeLocation(PositionParser.parse(context.getFilesRepository(), token), line.strip());

		} catch (final IOException | RuntimeException e) {
			return null;
		}
	}

	/** The name relative may go by now, if this edit renamed that very file. */
	private static String pathAfter(final String relative, final AppliedEdit applied) {
		for (final ResourceOperation operation : applied.resourceOperations())
			if (operation.kind() == ResourceOperationKind.RENAME && operation.path().equals(relative))
				return operation.newPath();

		return relative;
	}

	/** The 1-based column of the only whole-word occurrence of name in line, or -1 if there is not exactly one. */
	private static int soleWholeWordColumn(final String line, final String name) {
		int found = -1;
		int from = 0;
		while (true) {
			final int at = line.indexOf(name, from);
			if (at == -1)
				return found;

			from = at + name.length();
			final boolean leftClear = at == 0 || isWordCharacter(line.charAt(at - 1)) == false;
			final boolean rightClear = from == line.length() || isWordCharacter(line.charAt(from)) == false;
			if (leftClear && rightClear) {
				if (found != -1)
					return -1;

				found = at + 1;
			}
		}
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.Rename renamed -> {
			final Listing<String> files = renamed.changedFiles();
			if (files.totalCount() == 0 && renamed.fileRenames().isEmpty())
				yield "rename: nothing to change - '" + renamed.subject() + "' is already called '"
						+ renamed.newName() + "'";

			final StringBuilder out = new StringBuilder();
			out.append("rename: ").append(renamed.subject()).append(" -> ").append(renamed.newName()).append(", ")
					.append(files.summarize("file"));
			for (final String file : files.items())
				out.append('\n').append(file);

			for (final CommandPayload.Rename.FileRenaming renaming : renamed.fileRenames())
				out.append("\nfile renamed: ").append(renaming.from()).append(" -> ").append(renaming.to());

			if (renamed.declaration() != null)
				out.append("\ndeclaration now at ").append(renamed.declaration().display());

			out.append("\nrebuilt: ").append(renamed.errorCount()).append(" error(s)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
