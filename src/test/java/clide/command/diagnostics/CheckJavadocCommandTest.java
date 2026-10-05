package clide.command.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.model.Listing;

/**
 * Tests de CheckJavadocCommand. Contrairement à la plupart des autres
 * commandes, needsJdtlsSession() est false ici et executeCommand() ne touche
 * jamais context.getCurrentSession() - donc un run complet (pas seulement la
 * validation des paramètres) se teste avec un ClideContext dont la session
 * est null, sur un vrai petit projet écrit dans un @TempDir. C'est aussi ce
 * qui confirme empiriquement, dans le même mouvement, la déduplication:
 * javax.tools.ToolProvider.getSystemDocumentationTool() rapporte réellement le
 * même {@literal @}link cassé plusieurs fois (une fois par page HTML générée
 * qui le mentionne) - voir CheckJavadocCommand, "Deduplication".
 */
class CheckJavadocCommandTest {

	// ------------------------------------------------------------------
	// Avant que le vrai outil javadoc ne soit jamais lancé
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un <path regex> qui ne compile pas est refusé avant même de chercher un fichier")
	void anInvalidRegexIsRefused(@TempDir final Path root) throws IOException {
		final CommandResult result = checkJavadoc(root, "[");

		assertEquals(ErrorCode.INVALID_REGEX, result.code());
	}

	@Test
	@DisplayName("un <path regex> qui ne matche aucun fichier du projet est NO_FILES_FOUND")
	void aRegexMatchingNoFileIsRefused(@TempDir final Path root) throws IOException {
		Files.writeString(root.resolve("Square.java"), "package demo;\nclass Square {}\n", StandardCharsets.UTF_8);

		final CommandResult result = checkJavadoc(root, "NoSuchFile\\.java");

		assertEquals(ErrorCode.NO_FILES_FOUND, result.code());
		assertTrue(result.message().contains("NoSuchFile"), result.message());
	}

	// ------------------------------------------------------------------
	// Un vrai run, de bout en bout - possible ici, contrairement à la plupart
	// des autres commandes, précisément parce que celle-ci ne touche jamais
	// jdtls
	// ------------------------------------------------------------------

	@Test
	@DisplayName("un fichier sans problème de javadoc rend 0 finding, pas une erreur")
	void aCleanFileHasNoFindings(@TempDir final Path root) throws IOException {
		writeJavaFile(root, "demo", "Foo", """
				package demo;

				/** A perfectly ordinary class. */
				public class Foo {
					public void doThing() {
					}
				}
				""");

		final CommandResult result = checkJavadoc(root, "Foo\\.java");

		assertEquals(ErrorCode.NONE, result.code());
		final CommandPayload.JavadocCheck check = (CommandPayload.JavadocCheck) result.payload();
		assertEquals(1, check.matchedFileCount());
		assertEquals(0, check.diagnostics().totalCount());
		assertEquals(0, check.errorCount());
		assertEquals(0, check.warningCount());
	}

	@Test
	@DisplayName("un {@link} vers une classe qui n'existe pas est rapporté une seule fois, malgré le doclet qui le répète")
	void aBrokenLinkIsReportedOnceDespiteDocletRepeatingIt(@TempDir final Path root) throws IOException {
		writeJavaFile(root, "demo", "Foo", """
				package demo;

				/**
				 * Uses the legacy {@link demo.Bar} concept.
				 */
				public class Foo {
					public void doThing() {
					}
				}
				""");

		final CommandResult result = checkJavadoc(root, "Foo\\.java");

		assertEquals(ErrorCode.NONE, result.code());
		final CommandPayload.JavadocCheck check = (CommandPayload.JavadocCheck) result.payload();
		assertEquals(1, check.matchedFileCount());
		assertEquals(1, check.diagnostics().totalCount(), "the doclet reports this 4 times over - deduplicated to 1");
		assertEquals(0, check.errorCount());
		assertEquals(1, check.warningCount());

		final clide.model.Diagnostic finding = check.diagnostics().items().get(0);
		assertEquals("demo/Foo.java", finding.path());
		assertEquals(clide.model.Diagnostic.Severity.WARNING, finding.severity());
		assertTrue(finding.message().contains("demo.Bar"), finding.message());
	}

	@Test
	@DisplayName("un {@link} résolu ailleurs dans le projet, hors du <path regex>, n'est pas signalé comme cassé")
	void aLinkResolvedOutsideTheMatchedSetIsNotReportedAsBroken(@TempDir final Path root) throws IOException {
		// Bar exists in the project, just not in the file <path regex> matches below -
		// so the sourcepath must still cover it, or this would false-positive.
		writeJavaFile(root, "demo", "Bar", "package demo;\n\npublic class Bar {\n}\n");
		writeJavaFile(root, "demo", "Foo", """
				package demo;

				/** Refers to {@link demo.Bar}, defined in another file. */
				public class Foo {
				}
				""");

		final CommandResult result = checkJavadoc(root, "Foo\\.java");

		final CommandPayload.JavadocCheck check = (CommandPayload.JavadocCheck) result.payload();
		assertEquals(0, check.diagnostics().totalCount(), check.diagnostics().items().toString());
	}

	// ------------------------------------------------------------------
	// mapDiagnostic() - la conversion javax.tools.Diagnostic -> clide.model.Diagnostic
	// ------------------------------------------------------------------

	@Test
	@DisplayName("mapDiagnostic() rend null pour un diagnostic sans fichier source (le résumé du doclet, par exemple)")
	void mapDiagnosticDropsSourcelessDiagnostics(@TempDir final Path root) {
		final Diagnostic<JavaFileObject> summary = fakeDiagnostic(Diagnostic.Kind.NOTE, null, -1, "4 warnings");

		assertNull(CheckJavadocCommand.mapDiagnostic(summary, root));
	}

	@Test
	@DisplayName("mapDiagnostic() relativise le chemin au projet et traduit ERROR/WARNING/MANDATORY_WARNING")
	void mapDiagnosticRelativizesPathAndMapsSeverity(@TempDir final Path root) throws IOException {
		final Path file = root.resolve("demo/Foo.java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "package demo;\nclass Foo {}\n", StandardCharsets.UTF_8);
		final JavaFileObject source = fakeSource(file);

		final clide.model.Diagnostic error = CheckJavadocCommand
				.mapDiagnostic(fakeDiagnostic(Diagnostic.Kind.ERROR, source, 3, "boom"), root);
		assertEquals(new clide.model.Diagnostic("demo/Foo.java", 3, clide.model.Diagnostic.Severity.ERROR, "boom"),
				error);

		final clide.model.Diagnostic mandatoryWarning = CheckJavadocCommand
				.mapDiagnostic(fakeDiagnostic(Diagnostic.Kind.MANDATORY_WARNING, source, 5, "careful"), root);
		assertEquals(clide.model.Diagnostic.Severity.WARNING, mandatoryWarning.severity());
	}

	// ------------------------------------------------------------------
	// Rendu
	// ------------------------------------------------------------------

	@Test
	@DisplayName("des fichiers matchés mais tous propres le disent, plutôt que d'annoncer 0 finding sans contexte")
	void matchedFilesWithNoFindingsSaySo() {
		final CommandPayload payload = new CommandPayload.JavadocCheck(2, Listing.of(List.of(), 100), 0, 0, 0);

		assertEquals("check_javadoc: 2 file(s) matched, no javadoc problems found",
				new CheckJavadocCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	@Test
	@DisplayName("le compte rendu groupe les findings par fichier et se termine par la tally")
	void theReportGroupsFindingsByFileAndEndsWithATally() {
		final CommandPayload payload = new CommandPayload.JavadocCheck(1,
				Listing.of(List.of(new clide.model.Diagnostic("demo/Foo.java", 4,
						clide.model.Diagnostic.Severity.WARNING, "reference not found: demo.Bar")), 100),
				0, 1, 1);

		final String rendered = new CheckJavadocCommand().render(CommandResult.ok(payload), PrintMode.AI);

		assertEquals("""
				check_javadoc: 1 file(s) matched
				demo/Foo.java:
				  [warning] line 4: reference not found: demo.Bar
				javadoc: 0 error(s), 1 warning(s) in 1 file(s)""", rendered);
	}

	// ------------------------------------------------------------------
	// Outillage
	// ------------------------------------------------------------------

	private static CommandResult checkJavadoc(final Path root, final String pathRegex) throws IOException {
		final FilesRepository files_ = new FilesRepository(root, new Md5Repository(root));
		final ClideContext context = new ClideContext(files_, null, List.of());
		return new CheckJavadocCommand().executeCommand(context, pathRegex);
	}

	private static void writeJavaFile(final Path root, final String packageName, final String typeName,
			final String content) throws IOException {
		final Path dir = root.resolve(packageName.replace('.', '/'));
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(typeName + ".java"), content, StandardCharsets.UTF_8);
	}

	private static JavaFileObject fakeSource(final Path file) {
		return new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
			// javac's own file objects name themselves with the path as the platform
			// spells it; SimpleJavaFileObject gives the URI's path, "/C:/..." on Windows.
			@Override
			public String getName() {
				return file.toString();
			}
		};
	}

	/**
	 * A hand-written javax.tools.Diagnostic, not a mock - see this project's own
	 * move away from mock frameworks (CODING.md, HISTORY.md). Every method this
	 * test never reads throws, so a test that starts depending on one of them
	 * fails loudly rather than silently reading a made-up default.
	 */
	private static Diagnostic<JavaFileObject> fakeDiagnostic(final Diagnostic.Kind kind, final JavaFileObject source,
			final long line, final String message) {
		return new Diagnostic<>() {
			@Override
			public Kind getKind() {
				return kind;
			}

			@Override
			public JavaFileObject getSource() {
				return source;
			}

			@Override
			public long getPosition() {
				throw new UnsupportedOperationException();
			}

			@Override
			public long getStartPosition() {
				throw new UnsupportedOperationException();
			}

			@Override
			public long getEndPosition() {
				throw new UnsupportedOperationException();
			}

			@Override
			public long getLineNumber() {
				return line;
			}

			@Override
			public long getColumnNumber() {
				throw new UnsupportedOperationException();
			}

			@Override
			public String getCode() {
				throw new UnsupportedOperationException();
			}

			@Override
			public String getMessage(final Locale locale) {
				return message;
			}
		};
	}

}
