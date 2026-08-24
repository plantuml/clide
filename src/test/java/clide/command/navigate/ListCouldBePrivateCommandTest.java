package clide.command.navigate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.PrintMode;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.NarrowableMethod;
import clide.model.Position;

/**
 * Tests de ListCouldBePrivateCommand qui ne demandent aucun jdtls : le rendu
 * du compte rendu. L'analyse elle-même - références, hiérarchie de types,
 * limites du fichier - vit entièrement dans JdtlsSession.narrowableMethods()
 * et ne veut rien dire sans un vrai serveur en face ; voir HISTORY.md pour la
 * vérification en direct sur clide lui-même (act()/internalOnly()/
 * neverCalled()/usedExternally()/withParams(...), main() jamais listée,
 * equals()/toString() marqués comme redéfinissant Object).
 */
class ListCouldBePrivateCommandTest {

	@Test
	@DisplayName("le compte rendu liste chaque méthode, avec ses mentions 'never called' et 'implements/overrides'")
	void theReportListsEachMethodWithItsMentions() {
		final CodeLocation act = location("src/demo/Scratch.java", 6, 14, "act", "public void act() {");
		final CodeLocation internalOnly = location("src/demo/Scratch.java", 10, 14, "internalOnly",
				"public void internalOnly() {");

		final CommandPayload payload = new CommandPayload.NarrowableMethods("Scratch", Listing.of(List.of(
				new NarrowableMethod(act, List.of("ScratchIface"), true),
				new NarrowableMethod(internalOnly, List.of(), false)), 100));

		final String rendered = new ListCouldBePrivateCommand().render(CommandResult.ok(payload), PrintMode.AI);

		assertEquals("""
				list_could_be_private: Scratch, 2 method(s) could be private
				015fc03a:src/demo/Scratch.java:6:14:act public void act() {  (never called)  (implements/overrides ScratchIface - reducing visibility would not compile)
				015fc03a:src/demo/Scratch.java:10:14:internalOnly public void internalOnly() {""", rendered);
	}

	@Test
	@DisplayName("aucun candidat le dit explicitement, plutôt que d'annoncer une liste vide")
	void noCandidatesSaysSoExplicitly() {
		final CommandPayload payload = new CommandPayload.NarrowableMethods("Scratch", Listing.of(List.of(), 100));

		assertEquals("list_could_be_private: Scratch has no public method that looks safe to narrow to private",
				new ListCouldBePrivateCommand().render(CommandResult.ok(payload), PrintMode.AI));
	}

	private static CodeLocation location(final String path, final int line, final int column, final String name,
			final String lineText) {
		return new CodeLocation(new Position("015fc03a", path, line, column, name), lineText);
	}

}
