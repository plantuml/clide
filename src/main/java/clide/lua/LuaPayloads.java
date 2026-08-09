package clide.lua;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import clide.command.answer.CommandPayload;
import clide.command.answer.CommandSummary;
import clide.model.CodeLocation;
import clide.model.Diagnostic;
import clide.model.DiagnosticsReport;
import clide.model.Listing;
import clide.model.Position;
import clide.model.SearchMatch;
import clide.model.SymbolHit;
import clide.model.TestOutcome;

/**
 * What a command found, as a Lua table: the mirror of Command.render(), which
 * turns the same payload into the text a person reads. One produces prose, the
 * other a value a script can index - and both read the same CommandPayload, so
 * neither has to reparse what the other wrote.
 *
 * Java side only: this builds a tree of Map, List, String, Long, Double and
 * Boolean, and luajava's Conversion.FULL turns that into real Lua tables when
 * the tree is pushed (see LuaBridge). Nothing here touches a Lua stack, which is
 * also what makes it testable without a Lua state at all.
 *
 * <b>The switch is exhaustive and has no default.</b> CommandPayload is sealed,
 * so adding a payload without adding its case here does not compile - which is
 * the whole reason the payload hierarchy is sealed rather than a map of strings
 * to objects. A missing case would otherwise surface as a script reading nil off
 * a field that was never written, in production, on the one command nobody
 * tested.
 *
 * Conventions, fixed here once so no script has to discover them:
 * <ul>
 * <li>A Listing becomes {items, totalCount, maxResults, truncated} rather than a
 * bare array: the count a script must branch on is the total, not #items, and
 * flattening the listing to its items would hide exactly that.</li>
 * <li>A Java enum becomes its name in lower case ("error", "opened", "passed"),
 * matching the kind labels jdtls already hands over in that form.</li>
 * <li>An empty string stays an empty string. Turning "" into nil would make a
 * field that means "does not apply here" indistinguishable from one nobody
 * wrote, and a nil assigned to a Lua table key deletes it.</li>
 * <li>A null Java reference becomes nil - the only one today being
 * SymbolHit.location, which a script has to test rather than assume.</li>
 * </ul>
 */
public final class LuaPayloads {

	private LuaPayloads() {
	}

	public static Object toLua(final CommandPayload payload) {
		return switch (payload) {
		case CommandPayload.Nothing ignored -> map();
		case CommandPayload.Text text -> map("text", text.text());
		case CommandPayload.Locations found -> map("subject", found.subject(), "locations",
				listing(found.locations(), LuaPayloads::codeLocation));
		case CommandPayload.Symbols found -> map("subject", found.subject(), "symbols",
				listing(found.symbols(), LuaPayloads::symbolHit));
		case CommandPayload.SearchMatches found -> map("matches",
				listing(found.matches(), LuaPayloads::searchMatch), "fileCount", (long) found.fileCount());
		case CommandPayload.Diagnostics found -> map("report", diagnosticsReport(found.report()));
		case CommandPayload.Rebuild built -> map("changedFiles", (long) built.changedFiles(), "elapsedMillis",
				built.elapsedMillis(), "report", diagnosticsReport(built.report()));
		case CommandPayload.TestRun run -> testRun(run);
		case CommandPayload.Transaction transaction -> map("id", transaction.id(), "action",
				name(transaction.action()), "path", transaction.path());
		case CommandPayload.ModifiedFiles modified -> map("transactionId", modified.transactionId(), "files",
				listing(modified.files(), file -> file));
		case CommandPayload.Diff diff -> map("transactionId", diff.transactionId(), "path", diff.path(),
				"unifiedDiff", diff.unifiedDiff());
		case CommandPayload.CommandList commands -> map("commands",
				listing(commands.commands(), LuaPayloads::commandSummary));
		case CommandPayload.Setting setting -> map("name", setting.name(), "previousValue", setting.previousValue(),
				"newValue", setting.newValue());
		};
	}

	// ------------------------------------------------------------------
	// The records payloads are made of
	// ------------------------------------------------------------------

	private static <T> Object listing(final Listing<T> listing, final java.util.function.Function<T, Object> item) {
		final List<Object> items = new ArrayList<>();
		for (final T entry : listing.items())
			items.add(item.apply(entry));

		return map("items", items, "totalCount", (long) listing.totalCount(), "maxResults",
				(long) listing.maxResults(), "truncated", listing.truncated());
	}

	private static Object position(final Position position) {
		if (position == null)
			return null;

		// md5 travels in the table for the same reason it travels in the printed
		// token: a script's natural move is to keep a position and pass it back
		// later, and without the signature that round trip is exactly the one path
		// left where a stale position would go unchecked.
		return map("md5", position.md5(), "path", position.path(), "line", (long) position.line(), "column",
				(long) position.column(), "name", position.name());
	}

	private static Object codeLocation(final CodeLocation location) {
		if (location == null)
			return null;

		return map("position", position(location.position()), "lineText", location.lineText());
	}

	private static Object symbolHit(final SymbolHit symbol) {
		// location is nullable - jdtls does return symbols without one. It stays
		// nil here rather than becoming an empty table, so a script has to say what
		// it does about them instead of silently reading zeroes.
		return map("kind", symbol.kind(), "name", symbol.name(), "location", codeLocation(symbol.location()));
	}

	private static Object searchMatch(final SearchMatch match) {
		return map("path", match.path(), "line", (long) match.line(), "text", match.text());
	}

	private static Object diagnostic(final Diagnostic diagnostic) {
		return map("path", diagnostic.path(), "line", (long) diagnostic.line(), "severity",
				name(diagnostic.severity()), "message", diagnostic.message());
	}

	private static Object diagnosticsReport(final DiagnosticsReport report) {
		final Map<String, Object> out = new LinkedHashMap<>();
		out.put("diagnostics", listing(report.diagnostics(), LuaPayloads::diagnostic));
		out.put("errorCount", (long) report.errorCount());
		out.put("warningCount", (long) report.warningCount());
		out.put("fileCount", (long) report.fileCount());
		out.put("errorsOnly", report.errorsOnly());
		// tracked false means "nothing was analyzed", which is not "analyzed and
		// clean" - a script checking errorCount alone would read the two the same.
		out.put("tracked", report.tracked());
		out.put("clean", report.isClean());
		return out;
	}

	private static Object testOutcome(final TestOutcome outcome) {
		return map("status", name(outcome.status()), "name", outcome.name(), "location", outcome.location(),
				"messageLines", new ArrayList<Object>(outcome.messageLines()), "origin", outcome.origin());
	}

	private static Object testRun(final CommandPayload.TestRun run) {
		final Map<String, Object> out = new LinkedHashMap<>();
		out.put("subject", run.subject());
		out.put("passed", (long) run.passed());
		out.put("failed", (long) run.failed());
		out.put("skipped", (long) run.skipped());
		out.put("total", (long) run.total());
		out.put("elapsedMillis", run.elapsedMillis());
		out.put("tests", listing(run.tests(), LuaPayloads::testOutcome));
		out.put("failuresOnly", run.failuresOnly());
		return out;
	}

	private static Object commandSummary(final CommandSummary summary) {
		return map("keyword", summary.keyword(), "parameters", new ArrayList<Object>(summary.parameters()), "help",
				summary.help());
	}

	// ------------------------------------------------------------------
	// Plumbing
	// ------------------------------------------------------------------

	private static String name(final Enum<?> value) {
		return value == null ? null : value.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * A table, written as alternating key/value pairs. LinkedHashMap rather than
	 * HashMap so a table printed while debugging comes out in the order the record
	 * declares its fields - Lua itself gives no order to a keyed table, but a
	 * stable one is easier to read than a shuffled one.
	 */
	private static Map<String, Object> map(final Object... keysAndValues) {
		if (keysAndValues.length % 2 != 0)
			throw new IllegalArgumentException("expected alternating keys and values");

		final Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2)
			out.put((String) keysAndValues[i], keysAndValues[i + 1]);

		return out;
	}

}
