package clide.command;

import java.util.List;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.jdtls.JdtlsSession;
import clide.result.CommandPayload;
import clide.result.CommandResult;
import clide.result.ErrorCode;
import clide.result.Listing;
import clide.result.SymbolHit;

/**
 * workspace/symbol: finds symbols by name anywhere in the project, without
 * already knowing which file/line they're in - exactly the lookup goto_* itself
 * needs (file path + line + symbol). Matching is entirely jdtls' own (typically
 * fuzzy/camelCase, not exact) - clide applies no filtering of its own on top of
 * it, by design (see CLAUDE.md).
 */
public class FindSymbolCommand extends Command {

	@Keyword("find_symbol")
	@Help("Finds symbols by name anywhere in the project - matching is jdtls' own (typically fuzzy/camelCase, not exact). Use this to locate the <file path>/<line>/<column> that find_declaration/find_reference/find_implementation need.")
	@Param(type = ParamType.SINGLE_LINE, description = "Name")
	@Manual("""
			NAME
				find_symbol - find symbols by name anywhere in the project

			SYNOPSIS
				find_symbol <name>

			DESCRIPTION
				Finds every symbol named <name> anywhere in the project,
				without needing to already know which file or line it lives
				on. Matching is typically fuzzy/camelCase rather than an
				exact match, so a broad <name> can return more than one
				result. Meant to locate the
				<file path>:<line>:<column> that find_declaration,
				find_reference and find_implementation all need as input:
				run find_symbol first, then append ":<name>" to the
				location it prints and feed that into whichever of those
				actually answers your question.

			SEE ALSO
				find_declaration(1), find_reference(1),
				find_implementation(1)
			""")
	public FindSymbolCommand() {

	}

	@Override
	public CommandResult executeCommand(final ClideContext context, final String... params) {
		final JdtlsSession session = context.getCurrentSession();

		final String query = params[0];
		if (query.isEmpty())
			return CommandResult.error(ErrorCode.EMPTY_PARAMETER, "find_symbol needs a <name> to look for");

		try {
			return CommandResult.ok(
					new CommandPayload.Symbols(query, Listing.of(session.findSymbol(query), context.getMaxResults())));
		} catch (final Exception e) {
			return CommandResult.error(ErrorCode.JDTLS_REQUEST_FAILED, "find_symbol failed: " + e.getMessage());
		}
	}

	/**
	 * An empty result says so *and* says why it might be empty for a reason that
	 * has nothing to do with the project: find_symbol never matches a field by its
	 * name (a jdtls limitation, see CLAUDE.md), so "no symbol found" on a field is
	 * expected rather than informative. Naming a known blind spot at the moment it
	 * bites beats leaving a caller to conclude the field does not exist.
	 */
	@Override
	public String render(final CommandResult result, final PrintMode printMode) {
		if (result.payload() instanceof CommandPayload.Symbols found) {
			final Listing<SymbolHit> symbols = found.symbols();
			if (symbols.totalCount() == 0)
				return "find_symbol: no symbol found for \"" + found.subject()
						+ "\" - note that find_symbol matches types and methods only, never a field name";

			final StringBuilder out = new StringBuilder();
			out.append("find_symbol: ").append(symbols.summarize("symbol"));
			for (final SymbolHit symbol : symbols.items())
				out.append('\n').append(symbol.display());

			return out.toString();
		}

		return "";
	}

}
