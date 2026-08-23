package clide.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.command.answer.CommandResult;
import clide.command.answer.CommandStatus;
import clide.command.answer.ErrorCode;
import clide.command.diagnostics.PrintDiagnosticsCommand;
import clide.command.diagnostics.RebuildCommand;
import clide.command.testrun.RunTestsCommand;
import clide.core.ClideContext;
import clide.core.FilesRepository;

/**
 * run_tests, rebuild and print_diagnostics all take a single
 * &lt;all|failures&gt; / &lt;all|errors&gt; filter, declared as ParamType.SINGLE_LINE
 * (free text) because there is no fixed-vocabulary ParamType. Each used to read
 * that value with a bare equals("failures")/equals("errors") check: anything
 * that wasn't the one literal it looked for was silently treated as "all",
 * including a typo - "toto" ran exactly like "all", with no error, no hint,
 * nothing telling the caller their filter was never recognised.
 *
 * The three commands now reject an unrecognised filter the same way
 * find_declaration/find_reference/find_implementation already reject an
 * unrecognised &lt;what&gt;, via CommandResults.rejectUnlessOneOf() - see this
 * class's own contract. This test is what pins that down: it does not need a
 * live jdtls session because the rejection happens before any of the three
 * commands touches context.getCurrentSession().
 */
class FilterParamValidationTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	@Test
	@DisplayName("run_tests refuse un filtre autre que all/failures, sans toucher a jdtls")
	void runTestsRejectsAnInvalidFilter(@TempDir final Path root) {
		final CommandResult result = new RunTestsCommand().executeCommand(contextOn(root), "toto");

		assertEquals(CommandStatus.ERROR, result.status());
		assertEquals(ErrorCode.INVALID_ENUM_VALUE, result.code());
	}

	@Test
	@DisplayName("run_tests accepte all et failures - rejectUnlessOneOf ne les bloque pas")
	void runTestsAcceptsItsOwnVocabulary() {
		// A positive call to executeCommand() itself needs a real jdtls session
		// (runEverything() goes on to read the test classpath from it) - out of
		// reach for a plain unit test, and beside the point here. What this pins
		// down is only the check this fix actually adds: "all"/"failures" both
		// pass CommandResults.rejectUnlessOneOf(), the same helper the rejection
		// tests above prove refuses anything else.
		assertEquals(null, CommandResults.rejectUnlessOneOf("filter", "all", "all", "failures"));
		assertEquals(null, CommandResults.rejectUnlessOneOf("filter", "failures", "all", "failures"));
	}

	@Test
	@DisplayName("rebuild refuse un filtre autre que all/errors, avant meme de lancer un build")
	void rebuildRejectsAnInvalidFilter(@TempDir final Path root) {
		final CommandResult result = new RebuildCommand().executeCommand(contextOn(root), "toto");

		assertEquals(CommandStatus.ERROR, result.status());
		assertEquals(ErrorCode.INVALID_ENUM_VALUE, result.code());
	}

	@Test
	@DisplayName("print_diagnostics refuse un filtre autre que all/errors, sans toucher a jdtls")
	void printDiagnosticsRejectsAnInvalidFilter(@TempDir final Path root) {
		final CommandResult result = new PrintDiagnosticsCommand().executeCommand(contextOn(root), "toto");

		assertEquals(CommandStatus.ERROR, result.status());
		assertEquals(ErrorCode.INVALID_ENUM_VALUE, result.code());
	}

}
