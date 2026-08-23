package clide.command.navigate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import clide.annotation.ParamType;
import clide.command.answer.CommandResult;
import clide.command.answer.CommandStatus;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.FilesRepository;

/**
 * find_callers, find_callees, find_supertypes and find_subtypes - the four
 * call/type hierarchy commands added on top of jdtls' textDocument/
 * prepareCallHierarchy|prepareTypeHierarchy (see HISTORY.md, "Prochaines
 * etapes envisagees").
 *
 * What this pins down without a live jdtls session (none of the four is
 * exercised end-to-end here - see JDTLS.md for that): each is registered
 * with the right shape (keyword, a single <position> parameter, no leading
 * <what> - see the class docs on why one is not needed here the way it is
 * for find_declaration/find_reference/find_implementation), and each
 * refuses a malformed <position> - MALFORMED_POSITION - before ever calling
 * context.getCurrentSession(), the same way FilterParamValidationTest pins
 * down run_tests/rebuild/print_diagnostics refusing an invalid <filter>
 * before touching jdtls.
 *
 * "toto!" rather than the simpler "toto" as the malformed token: since
 * SYMBOLS.md's Classe/Outer.Inner notation, "toto" alone is now well-formed
 * - a bare class name PositionParser would search jdtls for, which is
 * exactly what these tests must not trigger with a null session (see
 * contextOn()). The trailing '!' is not legal in any of the four notations
 * (see PositionParser), so it stays MALFORMED_POSITION - caught by
 * PositionParser.preValidate()/parse() on grammar alone, without ever
 * reaching jdtls.
 */
class HierarchyCommandsTest {

	private static ClideContext contextOn(final Path root) {
		return new ClideContext(new FilesRepository(root, null), null, List.of());
	}

	@Test
	@DisplayName("les 4 commandes prennent exactement un parametre <position>")
	void eachTakesExactlyOnePositionParameter() {
		for (final Command command : List.of(new FindCallersCommand(), new FindCalleesCommand(),
				new FindSupertypesCommand(), new FindSubtypesCommand())) {
			assertEquals(1, command.paramSize(), command.getKeyword() + " prend un seul parametre");
			assertEquals(ParamType.POSITION, command.getParamTypes()[0],
					command.getKeyword() + " attend un <position>");
		}
	}

	@Test
	@DisplayName("find_callers refuse une position malformee avant de toucher jdtls")
	void findCallersRejectsAMalformedPosition(@TempDir final Path root) {
		assertMalformedPositionRejected(new FindCallersCommand().executeCommand(contextOn(root), "toto!"));
	}

	@Test
	@DisplayName("find_callees refuse une position malformee avant de toucher jdtls")
	void findCalleesRejectsAMalformedPosition(@TempDir final Path root) {
		assertMalformedPositionRejected(new FindCalleesCommand().executeCommand(contextOn(root), "toto!"));
	}

	@Test
	@DisplayName("find_supertypes refuse une position malformee avant de toucher jdtls")
	void findSupertypesRejectsAMalformedPosition(@TempDir final Path root) {
		assertMalformedPositionRejected(new FindSupertypesCommand().executeCommand(contextOn(root), "toto!"));
	}

	@Test
	@DisplayName("find_subtypes refuse une position malformee avant de toucher jdtls")
	void findSubtypesRejectsAMalformedPosition(@TempDir final Path root) {
		assertMalformedPositionRejected(new FindSubtypesCommand().executeCommand(contextOn(root), "toto!"));
	}

	private static void assertMalformedPositionRejected(final CommandResult result) {
		assertEquals(CommandStatus.ERROR, result.status());
		assertEquals(ErrorCode.MALFORMED_POSITION, result.code());
	}

}
