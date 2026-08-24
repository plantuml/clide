package clide.command.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import clide.edit.EditOperation;
import clide.edit.ResourceOperation;
import clide.edit.WorkspaceEdit;
import clide.jdtls.JdtlsSession;
import clide.jdtls.LspClient;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.Position;

/**
 * Moves a top-level class/interface/enum to another package: rewrites its
 * own package declaration, moves its file to match, and rewrites the import
 * of every other file jdtls can find that references it - requires an open
 * transaction.
 *
 * <h2>Built on the same request rename() itself is built on, one refactoring over</h2>
 *
 * jdtls answers workspace/willRenameFiles the same way it answers
 * textDocument/rename: a WorkspaceEdit, computed against the model of the
 * last build, applied by this command rather than by jdtls itself - see
 * JdtlsSession.willRenameFile(). The one difference measured empirically
 * (see HISTORY.md) is that this particular WorkspaceEdit never includes the
 * resource-rename operation for the file itself: jdtls only ever answers with
 * text edits (its own package line, and every importer's own import
 * statement it could find), and the physical move remains this command's own
 * responsibility - done by appending a single ResourceOperation.rename() to
 * what jdtls answered, then applying the combined edit through the same
 * WorkspaceEdit.applyTo() rename() already trusts for its own file-rename
 * case.
 *
 * <h2>What jdtls' own refactor cannot see</h2>
 *
 * A file in the class's OLD package that called it without an explicit
 * import - relying on same-package implicit visibility - is not touched by
 * workspace/willRenameFiles at all: nothing about the request even names
 * that file. It compiles today and will not after the move. This is a
 * confirmed limitation of jdtls' own refactor, not of this command, and
 * clide does not run an extra find_reference pass to patch around it: the
 * resulting error count, already part of every editing command's answer, is
 * how it surfaces here too - print_diagnostics gives the detail.
 *
 * <h2>One class per file, one file per call</h2>
 *
 * A file declaring more than one top-level type is refused outright
 * (MULTIPLE_TOP_LEVEL_TYPES) rather than silently moving every type in it
 * together - see siblingTopLevelTypeNames(). A nested type is refused too
 * (NOT_A_TOP_LEVEL_TYPE): moving one out of its enclosing type is a different
 * kind of refactoring this command does not attempt.
 */
public class MoveClassCommand extends Command {

	/** Same reserved-word set RenameCommand checks a new symbol name against - a package segment is refused the same way. */
	private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "_", "true", "false", "null");

	/**
	 * A file's own package declaration, read as plain text rather than asked of
	 * jdtls: this is pure local information (what a file says about itself),
	 * and reading it directly costs no round trip. Only the first matching line
	 * is used - a legal Java file has at most one package declaration.
	 */
	private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

	@Keyword("move_class")
	@Help("Moves the top-level class/interface/enum at <position> to <new package> - requires an open transaction.")
	@Param(type = ParamType.POSITION, description = "Position")
	@Param(type = ParamType.SINGLE_LINE, description = "New package")
	@Manual("""
			NAME
				move_class - move a top-level type to another package

			SYNOPSIS
				move_class <position> <new package>

			DESCRIPTION
				Moves the top-level class, interface or enum at
				<position> to <new package>: rewrites its own package
				declaration, moves its file to the matching directory,
				and rewrites the import of every other file jdtls can
				find that references it - the same
				workspace/willRenameFiles refactoring an IDE runs for a
				drag-and-drop package move.

				<new package> already equal to the type's current
				package is not an error - the answer says so and
				changes nothing.

				jdtls is told about the edit immediately, and the
				resulting error count is part of the answer, exactly
				like rename - print_diagnostics prints the detail
				without recompiling.

				Requires an open transaction: this writes files, and a
				transaction is what undoes them. Nothing is committed
				here - commit_transaction or rollback_transaction
				decides, and diff_transaction shows any single file
				first.

			WHAT IS NOT FIXED
				A file in the OLD package that called this class
				without an explicit import - relying on same-package
				implicit visibility - is not found by jdtls' own
				refactor and is left exactly as it was: it will not
				compile after the move. No extra detection pass is
				run for this; the error count in the answer is how it
				surfaces, and print_diagnostics or find_reference (on
				the fresh position this command returns) finds the
				file to fix by hand.

			ERRORS
				STALE_MODEL - files changed on disk and jdtls could
				not be told about them, so the move would have been
				computed against another state of the project.

				NOT_A_TOP_LEVEL_TYPE - <position> does not name a
				type declared at the top level of its file - a
				method, a field, or a type nested inside another one.

				MULTIPLE_TOP_LEVEL_TYPES - the file at <position>
				declares more than one top-level type. Moving it
				would silently move every type in the file, so the
				command refuses and names the others instead.

				INVALID_JAVA_PACKAGE_NAME - <new package> is not a
				dot-separated sequence of valid Java identifiers, or
				one of them is a reserved word.

				PACKAGE_DIRECTORY_MISMATCH - the file's own path does
				not match the package it declares, so move_class
				cannot reliably compute where it belongs after the
				move.

				DESTINATION_FILE_EXISTS - a file already exists where
				this move would place the class.

				NO_OPEN_TRANSACTION - see open_transaction.

				EDIT_NOT_APPLICABLE - the edit jdtls computed, or the
				file move itself, could not be applied to the files
				as they stand. Any file already written when this
				happens is undone by rolling the transaction back.

			SEE ALSO
				rename(1), find_reference(1), open_transaction(1),
				diff_transaction(1), rollback_transaction(1),
				print_diagnostics(1)
			""")
	public MoveClassCommand() {

	}

	/** Writes to the project - see the class doc. */
	@Override
	public boolean needsOpenTransaction() {
		return true;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final Position position;
		try {
			position = PositionParser.parse(context.getFilesRepository(), session, params[0]);
		} catch (final IllegalArgumentException e) {
			return CommandResults.positionFailure(e);
		} catch (final IOException | InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "move_class failed: " + e.getMessage());
		}

		final String newPackage = params[1].trim();
		final CommandResult badPackage = rejectUnlessJavaPackageName(newPackage);
		if (badPackage != null)
			return badPackage;

		try {
			final List<String> siblings = session.siblingTopLevelTypeNames(position);
			if (siblings.isEmpty() == false)
				return CommandResult.error(ErrorCode.MULTIPLE_TOP_LEVEL_TYPES,
						"'" + position.path() + "' declares more than one top-level type: "
								+ String.join(", ", siblings) + " - move_class only moves a file with exactly one");
		} catch (final IOException e) {
			// siblingTopLevelTypeNames() raises this exact IOException when position
			// does not name a type at the top level of its file - see its own doc.
			return CommandResult.error(ErrorCode.NOT_A_TOP_LEVEL_TYPE, e.getMessage());
		} catch (final InterruptedException | LspClient.TimeoutException e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "move_class failed: " + e.getMessage());
		}

		final Path projectRoot = context.getProjectRoot();
		final Path file = projectRoot.resolve(position.path());

		final String currentPackage;
		try {
			currentPackage = readDeclaredPackage(file);
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "move_class failed reading '" + position.path() + "': "
					+ e.getMessage());
		}

		final String simpleFileName = simpleNameOf(position.path());
		final String currentPackageAsPath = currentPackage.isEmpty() ? "" : currentPackage.replace('.', '/') + "/";
		final String expectedSuffix = currentPackageAsPath + simpleFileName;
		if (position.path().endsWith(expectedSuffix) == false)
			return CommandResult.error(ErrorCode.PACKAGE_DIRECTORY_MISMATCH,
					"'" + position.path() + "' declares package '" + currentPackage
							+ "', but its own path does not end with '" + expectedSuffix
							+ "' - move_class cannot compute where it belongs from a project layout that does not match the package declaration");

		if (newPackage.equals(currentPackage))
			return CommandResult.ok(new CommandPayload.MoveClass(position.name(), currentPackage, newPackage,
					position.path(), position.path(), Listing.of(List.of(), context.getMaxResults()),
					freshDeclaration(context, position, position.path()),
					session.diagnosticsReport(true, context.getMaxResults()).errorCount()));

		final String sourceRootPrefix = position.path().substring(0, position.path().length() - expectedSuffix.length());
		final String newPackageAsPath = newPackage.replace('.', '/') + "/";
		final String newRelative = sourceRootPrefix + newPackageAsPath + simpleFileName;

		final Path newFile = projectRoot.resolve(newRelative);
		if (Files.exists(newFile))
			return CommandResult.error(ErrorCode.DESTINATION_FILE_EXISTS,
					"'" + newRelative + "' already exists - move_class refuses to move onto an occupied name");

		final WorkspaceEdit jdtlsEdit;
		try {
			jdtlsEdit = session.willRenameFile(file, newFile);
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "move_class failed: " + e.getMessage());
		}

		final List<EditOperation> operations = new ArrayList<>(jdtlsEdit.operations());
		operations.add(ResourceOperation.rename(position.path(), newRelative));
		final WorkspaceEdit combined = new WorkspaceEdit(operations);

		try {
			final AppliedEdit applied = combined.applyTo(projectRoot);

			// Same reasoning as RenameCommand step 3: a notification is enough to bring
			// jdtls' own model, and the diagnostics it reports, back in step with what
			// this command just wrote - see JdtlsSession.refreshChangedFiles().
			session.refreshChangedFiles();

			return CommandResult.ok(new CommandPayload.MoveClass(position.name(), currentPackage, newPackage,
					position.path(), newRelative, Listing.of(applied.changedFiles(), context.getMaxResults()),
					freshDeclaration(context, position, newRelative),
					session.diagnosticsReport(true, context.getMaxResults()).errorCount()));

		} catch (final EditApplicationException e) {
			return CommandResult.error(ErrorCode.EDIT_NOT_APPLICABLE, "move_class failed: " + e.getMessage());
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "move_class failed while writing: " + e.getMessage());
		}
	}

	/**
	 * candidate, dot-segment by dot-segment, against the same identifier rules
	 * RenameCommand checks a new symbol name against - see
	 * RenameCommand.rejectUnlessJavaName(). Duplicated rather than shared for
	 * the same reason ResearchRegexCommand.displayPath() and
	 * RemoveUnusedImportsCommand's own helpers are: a handful of lines, easier
	 * to read twice than to introduce a shared utility class for.
	 */
	private static CommandResult rejectUnlessJavaPackageName(final String candidate) {
		if (candidate.isEmpty())
			return CommandResult.error(ErrorCode.INVALID_JAVA_PACKAGE_NAME, "<new package> is empty");

		for (final String segment : candidate.split("\\.", -1)) {
			if (segment.isEmpty())
				return CommandResult.error(ErrorCode.INVALID_JAVA_PACKAGE_NAME, "'" + candidate
						+ "' holds an empty segment - a package name is dot-separated identifiers, none of them empty");

			if (RESERVED.contains(segment))
				return CommandResult.error(ErrorCode.INVALID_JAVA_PACKAGE_NAME,
						"'" + segment + "' is a reserved word and cannot name a package segment");

			if (isWordStart(segment.charAt(0)) == false)
				return CommandResult.error(ErrorCode.INVALID_JAVA_PACKAGE_NAME,
						"'" + segment + "' does not start with a letter or an underscore");

			for (int i = 1; i < segment.length(); i++)
				if (isWordCharacter(segment.charAt(i)) == false)
					return CommandResult.error(ErrorCode.INVALID_JAVA_PACKAGE_NAME, "'" + segment + "' holds '"
							+ segment.charAt(i) + "', which is not a usable Java identifier character");
		}

		return null;
	}

	private static boolean isWordStart(final char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
	}

	private static boolean isWordCharacter(final char c) {
		return isWordStart(c) || (c >= '0' && c <= '9');
	}

	/**
	 * The package a file declares, read as plain text - "" when the file
	 * declares none (the default package). Package-private rather than
	 * private: no jdtls session is needed to exercise this, so it is worth its
	 * own unit test - see MoveClassCommandTest.
	 */
	static String readDeclaredPackage(final Path file) throws IOException {
		for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			final Matcher matcher = PACKAGE_LINE.matcher(line);
			if (matcher.find())
				return matcher.group(1);
		}
		return "";
	}

	/** The file name alone, off a project-relative, forward-slash path - "Scratch.java" out of "clide/scratcha/Scratch.java". */
	private static String simpleNameOf(final String path) {
		final int lastSlash = path.lastIndexOf('/');
		return lastSlash < 0 ? path : path.substring(lastSlash + 1);
	}

	/**
	 * Where the moved symbol now stands - derived, then checked, never
	 * guessed, exactly like RenameCommand.freshDeclaration(): the declaration
	 * stays on its own line (moving a file's package edits one line of it in
	 * place, it does not add or remove lines - confirmed empirically, see
	 * HISTORY.md), and movedToPath says which file to read it back from.
	 *
	 * Returns null rather than a best effort whenever anything is not
	 * clear-cut - the line does not hold the name exactly once, or the token
	 * does not validate.
	 */
	private static CodeLocation freshDeclaration(final ClideContext context, final Position before,
			final String movedToPath) {
		try {
			final Path file = context.getProjectRoot().resolve(movedToPath);
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (before.line() > lines.size())
				return null;

			final String line = lines.get(before.line() - 1);
			final int column = soleWholeWordColumn(line, before.name());
			if (column == -1)
				return null;

			final String token = Position.notation(Position.abbreviate(Md5Repository.md5Of(file)), movedToPath,
					before.line(), column, before.name());
			return new CodeLocation(PositionParser.parse(context.getFilesRepository(), token), line.strip());

		} catch (final IOException | RuntimeException e) {
			return null;
		}
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
		case CommandPayload.MoveClass moved -> {
			if (moved.fromPackage().equals(moved.toPackage()))
				yield "move_class: nothing to change - '" + moved.className() + "' is already in package '"
						+ moved.toPackage() + "'";

			final Listing<String> files = moved.changedFiles();
			final StringBuilder out = new StringBuilder();
			out.append("move_class: ").append(moved.className()).append(" ").append(moved.fromPackage())
					.append(" -> ").append(moved.toPackage()).append(", ").append(files.summarize("file"));
			for (final String changedFile : files.items())
				out.append('\n').append(changedFile);

			out.append("\nfile moved: ").append(moved.movedFrom()).append(" -> ").append(moved.movedTo());

			if (moved.declaration() != null)
				out.append("\ndeclaration now at ").append(moved.declaration().display());

			out.append("\nrebuilt: ").append(moved.errorCount()).append(" error(s)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
