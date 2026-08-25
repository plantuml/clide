package clide.command.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * <h2>What jdtls' own refactor cannot see, and what this command does about it</h2>
 *
 * A file in the class's OLD package that called it without an explicit
 * import - relying on same-package implicit visibility - is not touched by
 * workspace/willRenameFiles at all: nothing about the request even names
 * that file. Testing also caught jdtls occasionally missing a cross-package
 * importer too (see HISTORY.md). Both compile today and would not after the
 * move, left alone.
 *
 * Rather than leave this entirely to the caller, this command applies jdtls'
 * own edit first, rebuilds, and reads jdtls' own diagnostics back:
 * whichever file can no longer resolve the moved class's name gets exactly
 * one import added - never more, and never onto a file that already imports
 * a <i>different</i> class under the same simple name, a real conflict this
 * command will not guess its way out of (see addImportIfSafe()). That pass
 * only runs once the moved file's own package declaration has been read
 * back and found to actually say &lt;new package&gt; - the same indexing-race
 * flakiness that can leave a caller untouched (see HISTORY.md) can also
 * leave jdtls' edit to the moved file itself incomplete, and adding an
 * import for a class that is not really there yet would not be a fix. The
 * project is also required to already compile cleanly before any of this
 * starts (PROJECT_HAS_ERRORS otherwise) - without that, a non-zero error
 * count in the answer could not be told apart from one already there before
 * this command ran. What is still non-zero after both passes is what
 * genuinely could not be fixed - print_diagnostics gives the detail.
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

	/**
	 * A plain (non-static, non-wildcard) import line, capturing the fully
	 * qualified name it imports - what addImportIfSafe() reads a file's
	 * existing imports through, to decide whether adding one more is safe. A
	 * static or wildcard import is deliberately not matched: a static import
	 * names a member, not a type, and a wildcard's own simple names cannot be
	 * enumerated without resolving it - both left alone, same as
	 * RemoveUnusedImportsCommand leaves a wildcard already in use untouched.
	 */
	private static final Pattern PLAIN_IMPORT_LINE = Pattern.compile("^\\s*import\\s+([\\w.]+)\\s*;\\s*$");

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

				Once jdtls' own edit is applied, the moved file's own
				package declaration is read back and checked against
				<new package> before it is trusted for anything
				further - see WHAT IS STILL NOT FIXED below for why
				that read can disagree.

				<new package> already equal to the type's current
				package is not an error - the answer says so and
				changes nothing.

				The project is required to already compile cleanly
				before this runs at all (PROJECT_HAS_ERRORS otherwise)
				- without that, a non-zero error count afterwards
				could not be told apart from one that was already
				there. jdtls is told about the edit immediately, and
				whichever file jdtls' own diagnostics then say still
				cannot resolve the moved class's name gets one import
				added - reported as "import added" - before the
				resulting error count, part of the answer exactly like
				rename, is read; print_diagnostics prints the detail
				without recompiling.

				Requires an open transaction: this writes files, and a
				transaction is what undoes them. Nothing is committed
				here - commit_transaction or rollback_transaction
				decides, and diff_transaction shows any single file
				first.

			WHAT IS STILL NOT FIXED
				jdtls' own refactor does not always find every file
				that loses access to the moved class - most commonly
				one in the OLD package that called it without an
				explicit import, relying on same-package implicit
				visibility, but occasionally a cross-package importer
				too. This command patches every such file it can
				safely fix (see DESCRIPTION above), except one case it
				will not guess its way out of: a file that already
				imports a <i>different</i> class under the same simple
				name - a real name conflict, left exactly as it was.
				Any error still counted at the end is one of these,
				or something else jdtls' own refactor left broken -
				print_diagnostics or find_reference (on the fresh
				position this command returns) finds it to fix by
				hand.

				The import-adding pass itself also has one condition
				it will not proceed under: it trusts <new package> as
				the moved class's new location, and jdtls' own edit
				can - the same flakiness as above - occasionally leave
				the moved file's own package declaration unrewritten.
				When the moved file's declaration, read back after the
				edit, does not actually say <new package>, the whole
				pass is skipped rather than adding an import that would
				point at a class not really there yet - every gap is
				then left for errorCount and print_diagnostics to show,
				same as any other jdtls blind spot.

			ERRORS
				PROJECT_HAS_ERRORS - the project does not already
				compile cleanly. move_class refuses to run until it
				does, so the error count it reports afterwards means
				what this move broke, not what was already broken.

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

		// Read before anything else touches jdtls: without this, a non-zero
		// errorCount in the answer below could not be told apart from an error
		// already there before this command ran - see PROJECT_HAS_ERRORS and the
		// class doc.
		final int preexistingErrors = session.diagnosticsReport(true, context.getMaxResults()).errorCount();
		if (preexistingErrors > 0)
			return CommandResult.error(ErrorCode.PROJECT_HAS_ERRORS,
					"the project has " + preexistingErrors + " existing error(s) - move_class refuses to run until it compiles cleanly",
					"print_diagnostics shows the detail");

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
					Listing.of(List.of(), context.getMaxResults()), freshDeclaration(context, position, position.path()),
					preexistingErrors));

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

			// jdtls' own edit does not always find every file that loses access to the
			// moved class - see the class doc. Whatever jdtls' own diagnostics say
			// still cannot resolve the moved class's name, after the move, gets one
			// import added here - never more than that, and never onto a file that
			// already imports a different class under the same simple name (a real
			// conflict this command will not guess its way out of).
			//
			// This pass trusts newPackage as the moved class's new FQN - which only
			// holds if jdtls' own edit actually rewrote the moved file's package
			// declaration to match. It does not always (see the class doc and
			// HISTORY.md: the same indexing-race flakiness that can leave a caller
			// untouched can also leave the moved file's own package line untouched),
			// and adding an import that points at a class that does not actually
			// live there yet would be a fix that only looks like one. So this reads
			// the moved file's own declaration back before trusting newPackage at
			// all, and skips the whole pass - leaving every gap for errorCount and
			// print_diagnostics to report, same as any other jdtls blind spot - the
			// moment the two disagree.
			final List<String> importsAdded = new ArrayList<>();
			final String movedFileActualPackage = readDeclaredPackage(newFile);
			if (movedFileActualPackage.equals(newPackage))
				for (final String candidate : session.filesUnresolvedFor(position.name()))
					if (addImportIfSafe(projectRoot.resolve(candidate), position.name(), newPackage))
						importsAdded.add(candidate);

			if (importsAdded.isEmpty() == false)
				session.refreshChangedFiles();

			final Set<String> allChanged = new TreeSet<>(applied.changedFiles());
			allChanged.addAll(importsAdded);

			return CommandResult.ok(new CommandPayload.MoveClass(position.name(), currentPackage, newPackage,
					position.path(), newRelative, Listing.of(new ArrayList<>(allChanged), context.getMaxResults()),
					Listing.of(importsAdded, context.getMaxResults()), freshDeclaration(context, position, newRelative),
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
	 * Adds "import newPackage.simpleClassName;" to file, if doing so is
	 * unambiguous - false, and the file left untouched, otherwise. Two cases
	 * refuse: the file already carries that exact import (nothing to add, and
	 * if jdtls' diagnostics still called the name unresolved something else is
	 * wrong that this pass will not paper over), or it already imports a
	 * <i>different</i> class under the same simple name - a real conflict, not
	 * a gap this command can safely guess its way out of (see the class doc).
	 *
	 * The new line goes right after the last existing plain import - joining
	 * a block that already separates itself from the rest of the file - or,
	 * when the file has none, right after the package declaration (or at the
	 * very top, in the default package), with a blank line added on each side
	 * unless one is already there, matching every source file this project
	 * already writes. No reordering, no grouping - the smallest edit that
	 * makes the file compile, same philosophy as RemoveUnusedImportsCommand's
	 * own single-purpose edit.
	 *
	 * Package-private rather than private: this is the one piece of the
	 * auto-import pass that needs no jdtls session to exercise directly -
	 * everything else here is only meaningful against a live server, so only
	 * this is worth a dedicated unit test - see MoveClassCommandTest.
	 */
	static boolean addImportIfSafe(final Path file, final String simpleClassName, final String newPackage)
			throws IOException {
		final List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
		final String targetFqn = newPackage + "." + simpleClassName;

		int lastImportLine = -1;
		int packageLine = -1;
		for (int i = 0; i < lines.size(); i++) {
			final Matcher importMatcher = PLAIN_IMPORT_LINE.matcher(lines.get(i));
			if (importMatcher.matches()) {
				final String importedFqn = importMatcher.group(1);
				if (importedFqn.equals(targetFqn) || simpleNameOfFqn(importedFqn).equals(simpleClassName))
					return false;

				lastImportLine = i;
				continue;
			}

			if (packageLine == -1 && PACKAGE_LINE.matcher(lines.get(i)).find())
				packageLine = i;
		}

		final String importLine = "import " + targetFqn + ";";
		if (lastImportLine != -1) {
			// An existing import block already separates itself from whatever
			// follows - nothing more to ensure here.
			lines.add(lastImportLine + 1, importLine);
		} else if (packageLine != -1) {
			// No import block exists yet: this becomes the only import, so it needs
			// a blank line on both sides - before it (separating it from "package
			// ...;") and after it (separating it from whatever follows, typically
			// the type declaration) - unless one is already there.
			final boolean blankBeforeImport = packageLine + 1 < lines.size() && lines.get(packageLine + 1).isBlank();
			if (blankBeforeImport == false)
				lines.add(packageLine + 1, "");

			final int importIndex = packageLine + 2;
			lines.add(importIndex, importLine);

			final boolean blankAfterImport = importIndex + 1 < lines.size() && lines.get(importIndex + 1).isBlank();
			if (blankAfterImport == false)
				lines.add(importIndex + 1, "");
		} else {
			// The default package: no "package ...;" line to anchor against, so the
			// import goes at the very top, followed by a blank line separating it
			// from whatever follows - unless one is already there.
			lines.add(0, importLine);
			final boolean blankAfterImport = lines.size() > 1 && lines.get(1).isBlank();
			if (blankAfterImport == false)
				lines.add(1, "");
		}

		Files.write(file, lines, StandardCharsets.UTF_8);
		return true;
	}

	/** "java.util.List" -&gt; "List". */
	private static String simpleNameOfFqn(final String fqn) {
		final int lastDot = fqn.lastIndexOf('.');
		return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
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

			for (final String fixedFile : moved.importsAdded().items())
				out.append("\nimport added: ").append(fixedFile);

			if (moved.declaration() != null)
				out.append("\ndeclaration now at ").append(moved.declaration().display());

			out.append("\nrebuilt: ").append(moved.errorCount()).append(" error(s)");
			yield out.toString();
		}
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

}
