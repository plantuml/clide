package clide.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.CommandRepository;

/**
 * Quelles commandes acceptent de répondre alors que des fichiers ont bougé
 * depuis le dernier build (voir Command.needsFreshModel(),
 * CommandDispatcher.rejectIfModelIsStale()).
 *
 * Ce test énumère la liste des exemptées au lieu de vérifier une propriété,
 * parce que c'est bien une liste qu'il faut garder courte et justifiée : une
 * commande qui interroge jdtls et se dispense du contrôle est une commande qui
 * peut répondre juste sur un projet qui n'existe plus. Ajouter une exemption
 * doit demander de modifier ce test, et donc d'en écrire la raison.
 *
 * Ce qui n'est pas testé ici : le refus lui-même. Il tient à l'état d'une
 * session jdtls vivante — ready, avec un snapshot pris à son dernier build —
 * qu'aucun test unitaire ne peut fabriquer honnêtement. Il se vérifie de bout
 * en bout, sur un vrai daemon.
 */
class FreshModelRequirementTest {

	/**
	 * Les deux seules dont le contrat est justement de parler du dernier build :
	 * rebuild parce qu'il est le remède, print_diagnostics parce qu'il réaffiche
	 * ce que ce build a dit sans rien recompiler et ne prétend pas autre chose.
	 */
	private static final List<String> EXEMPTED = List.of("print_diagnostics", "rebuild");

	@Test
	@DisplayName("seules rebuild et print_diagnostics interrogent jdtls sans exiger un modèle à jour")
	void onlyRebuildAndPrintDiagnosticsAreExempted() {
		final TreeSet<String> exempted = new TreeSet<>();
		for (final Command command : CommandRepository.commands)
			if (command.needsJdtlsSession() && command.needsFreshModel() == false)
				exempted.add(command.getKeyword());

		assertEquals(EXEMPTED, List.copyOf(exempted));
	}

	@Test
	@DisplayName("toute commande sémantique ou modifiante exige un modèle à jour")
	void everySemanticOrModifyingCommandRequiresAFreshModel() {
		for (final String keyword : List.of("find_symbol", "find_declaration", "find_reference", "find_implementation",
				"hover", "list_members", "run_test", "run_tests", "rename")) {
			final Command command = commandNamed(keyword);
			assertTrue(command.needsJdtlsSession(), keyword + " interroge jdtls");
			assertTrue(command.needsFreshModel(), keyword + " exige un modele a jour");
		}
	}

	@Test
	@DisplayName("le contrôle ne coûte rien aux commandes qui ne parlent pas à jdtls")
	void commandsThatNeverAskJdtlsAreNotCharged() {
		// needsFreshModel() vaut true par defaut y compris pour celles-la, et c'est
		// sans effet : CommandDispatcher ne consulte le drapeau que dans la branche
		// needsJdtlsSession(). Le scan de fichiers n'est donc jamais paye par un
		// help, un exit ou un open_transaction.
		for (final String keyword : List.of("help", "man", "exit", "open_transaction", "search_regex"))
			assertFalse(commandNamed(keyword).needsJdtlsSession(), keyword + " ne parle pas a jdtls");
	}

	private static Command commandNamed(final String keyword) {
		for (final Command command : CommandRepository.commands)
			if (command.getKeyword().equals(keyword))
				return command;

		throw new AssertionError("aucune commande " + keyword);
	}

}
