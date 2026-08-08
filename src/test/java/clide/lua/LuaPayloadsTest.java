package clide.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import clide.command.answer.CommandPayload;
import clide.model.CodeLocation;
import clide.model.Listing;
import clide.model.Position;
import clide.model.SymbolHit;

/**
 * Tests de LuaPayloads - la forme exacte qu'un script voit.
 *
 * Ce sont des tests de contrat, au même titre que ceux de ResultEnvelope : le
 * nom de chaque clé est ce qu'un script écrit en dur, donc le changer casse
 * silencieusement tous les scripts déjà écrits. Les figer ici est le seul
 * endroit où ce changement devient visible.
 *
 * Rien ici ne démarre de runtime Lua : la conversion produit un arbre de Map et
 * de List que luajava traduira, et c'est cet arbre qui est le contrat.
 */
class LuaPayloadsTest {

	private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";

	@SuppressWarnings("unchecked")
	private static Map<String, Object> table(final Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> array(final Object value) {
		return (List<Object>) value;
	}

	@Test
	@DisplayName("une Listing garde son total, pas seulement ses items")
	void listingCarriesItsTotal() {
		final Position position = new Position(MD5, "src/Foo.java", 42, 17, "compute");
		final CodeLocation location = new CodeLocation(position, "int compute() {");
		final CommandPayload payload = new CommandPayload.Locations("compute",
				Listing.of(List.of(location, location, location), 2));

		final Map<String, Object> locations = table(table(LuaPayloads.toLua(payload)).get("locations"));

		// 2 items rendus, 3 trouvés : un script qui compte #items au lieu de
		// totalCount croirait avoir tout vu.
		assertEquals(2, array(locations.get("items")).size());
		assertEquals(3L, locations.get("totalCount"));
		assertEquals(2L, locations.get("maxResults"));
		assertEquals(true, locations.get("truncated"));
	}

	@Test
	@DisplayName("une position est la table {md5, path, line, column, name} que la notation épelle")
	void positionKeepsItsFiveFields() {
		final CommandPayload payload = new CommandPayload.Locations("compute",
				Listing.of(List.of(new CodeLocation(new Position(MD5, "src/Foo.java", 42, 17, "compute"), "int compute() {")),
						100));

		final Map<String, Object> first = table(
				array(table(table(LuaPayloads.toLua(payload)).get("locations")).get("items")).get(0));
		final Map<String, Object> position = table(first.get("position"));

		// md5 compris : sans lui dans la table, une position gardée par un script
		// puis repassée plus tard échapperait au contrôle de fraîcheur, alors que
		// c'est justement le scénario que le md5 existe pour couvrir.
		assertEquals(MD5, position.get("md5"));
		assertEquals("src/Foo.java", position.get("path"));
		assertEquals(42L, position.get("line"));
		assertEquals(17L, position.get("column"));
		assertEquals("compute", position.get("name"));
		assertEquals("int compute() {", first.get("lineText"));
	}

	@Test
	@DisplayName("un symbole sans emplacement le dit par nil, pas par une table vide")
	void missingLocationStaysNull() {
		final CommandPayload payload = new CommandPayload.Symbols("Foo",
				Listing.of(List.of(new SymbolHit("method", "compute", null)), 100));

		final Map<String, Object> symbol = table(
				array(table(table(LuaPayloads.toLua(payload)).get("symbols")).get("items")).get(0));

		assertEquals("method", symbol.get("kind"));
		assertEquals("compute", symbol.get("name"));
		// Une table vide se lirait comme un emplacement dont tous les champs sont
		// absents - un script la testerait comme présente et compterait faux.
		assertNull(symbol.get("location"));
	}

	@Test
	@DisplayName("un enum devient son nom en minuscules")
	void enumsAreLowerCase() {
		final CommandPayload payload = new CommandPayload.Transaction("$refactor_foo",
				CommandPayload.Transaction.Action.ROLLED_BACK, "");

		final Map<String, Object> transaction = table(LuaPayloads.toLua(payload));

		assertEquals("rolled_back", transaction.get("action"));
		// "" veut dire "ne s'applique pas ici" - le garder distingue ce cas d'une
		// clé que personne n'a écrite.
		assertEquals("", transaction.get("path"));
	}

	@Test
	@DisplayName("un rebuild rend ses compteurs, pas la phrase qui les résume")
	void rebuildCarriesItsCounts() {
		final CommandPayload payload = new CommandPayload.Rebuild(3, 9500L,
				new clide.model.DiagnosticsReport(Listing.empty(), 0, 2, 1, true, true));

		final Map<String, Object> rebuild = table(LuaPayloads.toLua(payload));
		final Map<String, Object> report = table(rebuild.get("report"));

		assertEquals(3L, rebuild.get("changedFiles"));
		assertEquals(9500L, rebuild.get("elapsedMillis"));
		assertEquals(0L, report.get("errorCount"));
		assertEquals(2L, report.get("warningCount"));
		assertEquals(true, report.get("tracked"));
		assertEquals(false, report.get("clean"));
	}

	@Test
	@DisplayName("une commande sans rien à dire rend une table vide, jamais nil")
	void nothingIsAnEmptyTable() {
		final Object converted = LuaPayloads.toLua(CommandPayload.NOTHING);

		assertTrue(table(converted).isEmpty());
	}

}
