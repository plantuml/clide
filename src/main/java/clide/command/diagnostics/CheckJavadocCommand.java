package clide.command.diagnostics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.SourceFile;
import clide.jdtls.EclipseDescriptorBuilder;
import clide.model.Diagnostic;
import clide.model.Listing;

/**
 * Runs the JDK's own javadoc tool (javax.tools.ToolProvider.
 * getSystemDocumentationTool(), bundled with every full JDK - see CODING.md's
 * note on clide itself needing one to build) over the project's own sources
 * and reports its diagnostics - a broken {@literal @}link chief among them.
 *
 * <h2>Why the real javadoc tool, not jdtls</h2>
 *
 * jdtls' own compiler (ECJ) can be made to validate javadoc comments too, but
 * two things ruled that out empirically. First, its messages are worded
 * differently from the ones the actual javadoc doclet produces - not what a
 * caller building against "what does 'gradle javadoc' warn about" wants read
 * back. Second, and more fundamental for a project like PlantUML: the
 * warnings a Gradle build shows often come from a *sub-module's* javadoc task
 * (a license-variant subproject compiling a preprocessed copy of the main
 * sources), and clide only ever opens a single project with jdtls - there is
 * no session for those sub-modules to validate against. Running the actual
 * javadoc tool, fresh, sidesteps both: it reads the same message catalog
 * Gradle's own javadoc task does (confirmed empirically - see HISTORY.md),
 * and it needs nothing already open or already built to run.
 *
 * <h2>sourcepath, not the narrower &lt;path regex&gt; match</h2>
 *
 * &lt;path regex&gt; only selects which files are actually <i>documented</i>
 * (the compilation units handed to the doclet) - it must not also limit what
 * the doclet can resolve a {@literal @}link *against*, or a perfectly valid
 * reference to a class outside the matched set would be reported as broken
 * for no better reason than clide's own filter. The doclet is instead given
 * the project's whole conventional source layout as its sourcepath - the
 * very same heuristic (src/main/java, src/test/java, ...) clide's own
 * .classpath generation already relies on, see EclipseDescriptorBuilder.
 *
 * One consequence worth knowing, confirmed empirically rather than assumed:
 * the standard doclet does not stop at the matched file either. It also
 * builds that file's own package-summary page, which means fully parsing
 * every other file in the same package - so a finding can end up attributed
 * to a sibling file &lt;path regex&gt; never matched on its own. Still
 * correctly attributed (file, line, message), still deduplicated the same
 * way - just not necessarily confined to the exact set &lt;path regex&gt;
 * named. Harmless on a project that already compiles cleanly (nothing left
 * for the doclet to trip on in a sibling file); worth knowing on one that
 * does not.
 *
 * <h2>No project classpath (yet)</h2>
 *
 * Only sourcepath is set - not a classpath of compiled/external
 * dependencies. A {@literal @}link naming a type that exists only in a
 * library outside the project's own sources (e.g. an Ant/OpenPDF class
 * compileOnly-referenced from a javadoc comment) will therefore read as
 * "reference not found" here even where it is not really broken. Accepted
 * for a v1: the failure mode this command exists to catch - a reference to a
 * type that no longer exists anywhere in the project's own source - is
 * unaffected, and a real false positive of this kind is easy to recognize
 * (the named type is a well-known external one, not a project class).
 *
 * <h2>Deduplication</h2>
 *
 * The standard doclet reports the same broken reference once per generated
 * page that mentions it (a class's own page, the package summary, the
 * all-classes index, ...) - confirmed empirically, four identical
 * diagnostics for one broken {@literal @}link in a single-class test. Kept
 * here by (path, line, message), the same triple a reader would use to tell
 * "the same problem again" from "a different one".
 *
 * <h2>Never touches jdtls</h2>
 *
 * needsJdtlsSession() is false: this is a self-contained check, entirely
 * separate from the project's jdtls session (if any is even running), the
 * same way search_regex never touches it either.
 */
public class CheckJavadocCommand extends Command {

	@Keyword("check_javadoc")
	@Help("Runs the real javadoc tool over every file whose path matches <path regex> and reports its diagnostics (e.g. a broken {@link}).")
	@Param(type = ParamType.REGEX, description = "Path regex")
	@Manual("""
			NAME
				check_javadoc - report javadoc problems (e.g. a broken {@link})

			SYNOPSIS
				check_javadoc <path regex>

			DESCRIPTION
				Walks every .java file under the project (the same scope
				search_regex/remove_unused_imports walk), keeps the ones
				whose project-relative path matches <path regex>, and runs
				them through the JDK's own javadoc tool
				(javax.tools.ToolProvider.getSystemDocumentationTool()) -
				the very tool Gradle's own javadoc task uses, so the
				messages read here are worded identically to what a
				"gradle javadoc" run would show ("reference not found:
				...", for instance).

				<path regex> only picks which files are documented - what
				a {@link} inside them can resolve *against* is always the
				project's whole conventional source layout (src/main/java,
				src/test/java, etc. - the same folders clide's own
				.classpath generation looks for), never narrowed to the
				matched set. Only the project's own sources are on that
				path: a reference to a type that exists solely in an
				external library (nothing check_javadoc adds a classpath
				for, in this version) can misreport as broken - see
				CheckJavadocCommand's class doc for why that trade-off was
				accepted.

				The doclet also builds each matched file's own
				package-summary page, which means fully parsing every
				other file in the same package - so a finding can name a
				sibling file <path regex> never matched on its own,
				correctly attributed and still deduplicated. Harmless on a
				project that already compiles cleanly.

				Never touches jdtls: this is a separate, self-contained
				check, not a language-server query, and runs whether or
				not a jdtls session is even up.

				Each finding is printed as "[severity] line <n>: message"
				under its file's own path, deduplicated by (file, line,
				message) - the doclet reports the same problem once per
				generated page that mentions it, which would otherwise
				print identical lines several times over for one real
				problem.

				The answer starts with how many files <path regex>
				matched, then ends with a tally - "javadoc: <n> error(s),
				<n> warning(s) in <n> file(s)" - counted over every
				(deduplicated) finding, never over the listing shown,
				exactly like print_diagnostics/rebuild's own tally.

			ERRORS
				INVALID_REGEX - <path regex> does not compile.

				NO_FILES_FOUND - <path regex> matched no file under the
				project. Distinct from matching files javadoc simply had
				nothing to say about, which is not an error.

				IO_FAILED - the javadoc run itself could not be started -
				most likely no javadoc tool in this JVM (a full JDK is
				required, a JRE alone has none - the same requirement
				building clide itself already has, see CODING.md), or a
				scratch directory could not be created.

			SEE ALSO
				print_diagnostics(1), rebuild(1), search_regex(1)
			""")
	public CheckJavadocCommand() {

	}

	/** A self-contained check on the project's own sources - see the class doc. */
	@Override
	public boolean needsJdtlsSession() {
		return false;
	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final Pattern pathPattern;
		try {
			pathPattern = Pattern.compile(params[0]);
		} catch (final PatternSyntaxException e) {
			return CommandResult.error(ErrorCode.INVALID_REGEX, "Invalid regex: " + e.getMessage());
		}

		final Path projectRoot = context.getProjectRoot();
		final List<Path> matched = new ArrayList<>();
		try {
			for (final SourceFile source : context.getFilesRepository().currentSourceFiles()) {
				final Path file = Path.of(source.sourceFilePath());
				if (pathPattern.matcher(displayPath(file, projectRoot)).find())
					matched.add(file);
			}
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "check_javadoc failed: " + e.getMessage());
		}
		matched.sort(Comparator.comparing(file -> displayPath(file, projectRoot)));

		if (matched.isEmpty())
			return CommandResult.error(ErrorCode.NO_FILES_FOUND,
					"No file under the project matches '" + params[0] + "'");

		final DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
		if (tool == null)
			return CommandResult.error(ErrorCode.IO_FAILED, "No javadoc tool available in this JVM - "
					+ "check_javadoc needs a full JDK (a JRE alone has none), same as building clide itself.");

		final List<File> sourcePath = sourcePath(projectRoot);

		final List<Diagnostic> found = new ArrayList<>();
		final Set<String> seen = new LinkedHashSet<>();
		final javax.tools.DiagnosticListener<JavaFileObject> listener = diagnostic -> {
			final Diagnostic mapped = mapDiagnostic(diagnostic, projectRoot);
			if (mapped != null && seen.add(mapped.path() + ":" + mapped.line() + ":" + mapped.message()))
				found.add(mapped);
		};

		final Path scratchOut;
		try {
			scratchOut = Files.createTempDirectory("clide-check_javadoc-");
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED,
					"check_javadoc could not create a scratch output directory: " + e.getMessage());
		}

		try (StandardJavaFileManager fileManager = tool.getStandardFileManager(listener, null,
				StandardCharsets.UTF_8)) {
			fileManager.setLocation(DocumentationTool.Location.DOCUMENTATION_OUTPUT, List.of(scratchOut.toFile()));
			fileManager.setLocation(StandardLocation.SOURCE_PATH, sourcePath);

			final List<File> units = new ArrayList<>();
			for (final Path file : matched)
				units.add(file.toAbsolutePath().toFile());

			final DocumentationTool.DocumentationTask task = tool.getTask(null, fileManager, listener, null,
					List.of("-Xdoclint:none", "-quiet"), fileManager.getJavaFileObjectsFromFiles(units));
			task.call();
		} catch (final IOException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "check_javadoc failed: " + e.getMessage());
		} catch (final RuntimeException e) {
			return CommandResult.error(ErrorCode.IO_FAILED, "check_javadoc failed: " + e.getMessage());
		} finally {
			deleteRecursively(scratchOut);
		}

		found.sort(Comparator.comparing(Diagnostic::path).thenComparingInt(Diagnostic::line));

		int errorCount = 0;
		int warningCount = 0;
		final Set<String> filesWithFindings = new LinkedHashSet<>();
		for (final Diagnostic diagnostic : found) {
			filesWithFindings.add(diagnostic.path());
			if (diagnostic.severity() == Diagnostic.Severity.ERROR)
				errorCount++;
			else if (diagnostic.severity() == Diagnostic.Severity.WARNING)
				warningCount++;
		}

		return CommandResult.ok(new CommandPayload.JavadocCheck(matched.size(),
				Listing.of(found, context.getMaxResults()), errorCount, warningCount, filesWithFindings.size()));
	}

	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		return switch (result.payload()) {
		case CommandPayload.JavadocCheck check -> render(check);
		default -> ResultEnvelope.unexpectedPayload(getKeyword(), result.payload());
		};
	}

	private static String render(final CommandPayload.JavadocCheck check) {
		final Listing<Diagnostic> diagnostics = check.diagnostics();
		if (diagnostics.totalCount() == 0)
			return "check_javadoc: " + check.matchedFileCount() + " file(s) matched, no javadoc problems found";

		final StringBuilder out = new StringBuilder();
		out.append("check_javadoc: ").append(check.matchedFileCount()).append(" file(s) matched\n");

		String currentFile = null;
		for (final Diagnostic diagnostic : diagnostics.items()) {
			if (diagnostic.path().equals(currentFile) == false) {
				currentFile = diagnostic.path();
				out.append(currentFile).append(":\n");
			}
			out.append("  ").append(diagnostic.display()).append('\n');
		}

		out.append("javadoc: ").append(check.errorCount()).append(" error(s), ").append(check.warningCount())
				.append(" warning(s) in ").append(check.fileCount()).append(" file(s)");
		if (diagnostics.truncated())
			out.append("\njavadoc: ").append(diagnostics.summarize("finding"));

		return out.toString();
	}

	/**
	 * The project's conventional source folders (see
	 * EclipseDescriptorBuilder.detectSourceFolders(), the same heuristic clide's
	 * own .classpath generation uses) as absolute directories, falling back to
	 * the project root itself if none of them exist - so a {@literal @}link can
	 * always resolve against *something*, even on a project laid out
	 * unconventionally enough that no folder was detected.
	 */
	private static List<File> sourcePath(final Path projectRoot) {
		final List<File> roots = new ArrayList<>();
		for (final String folder : EclipseDescriptorBuilder.forProject(projectRoot).detectSourceFolders())
			roots.add(projectRoot.resolve(folder).toFile());

		if (roots.isEmpty())
			roots.add(projectRoot.toFile());

		return roots;
	}

	/**
	 * javax.tools.Diagnostic to clide.model.Diagnostic, or null for one with no
	 * associated source file - the doclet's own end-of-run summary ("4
	 * warnings"), and anything else that is not about one specific file and
	 * line, is not a finding a caller can act on the way the rest of this
	 * command's answer is meant to be read.
	 *
	 * Package-private, not private: the one piece of this command exercised
	 * without a javadoc run behind it - see CheckJavadocCommandTest, which hands
	 * it a hand-written javax.tools.Diagnostic rather than a mock (see
	 * CODING.md's note on this project's own move away from mock frameworks).
	 */
	static Diagnostic mapDiagnostic(final javax.tools.Diagnostic<? extends JavaFileObject> diagnostic,
			final Path projectRoot) {
		final JavaFileObject source = diagnostic.getSource();
		if (source == null)
			return null;

		final String path = displayPath(Path.of(source.getName()), projectRoot);
		final int line = (int) Math.max(0, diagnostic.getLineNumber());
		final Diagnostic.Severity severity = switch (diagnostic.getKind()) {
		case ERROR -> Diagnostic.Severity.ERROR;
		case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
		default -> Diagnostic.Severity.INFO;
		};

		return new Diagnostic(path, line, severity, diagnostic.getMessage(null));
	}

	/**
	 * Best-effort cleanup of the scratch directory the doclet was told to write
	 * its (unused) HTML output into - never fails the command over it, since
	 * nothing downstream reads that directory and an orphaned temp dir left
	 * behind by a rare cleanup failure is the OS's problem, not check_javadoc's
	 * answer.
	 */
	private static void deleteRecursively(final Path root) {
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (final IOException e) {
					// best-effort - see method doc
				}
			});
		} catch (final IOException e) {
			// best-effort - see method doc
		}
	}

	/**
	 * file as the client should see it: relative to projectRoot, forward
	 * slashes - the same shape search_regex/remove_unused_imports's own
	 * displayPath() produces.
	 */
	private static String displayPath(final Path file, final Path projectRoot) {
		final Path relative = file.startsWith(projectRoot) ? projectRoot.relativize(file) : file;
		return relative.toString().replace('\\', '/');
	}

}
