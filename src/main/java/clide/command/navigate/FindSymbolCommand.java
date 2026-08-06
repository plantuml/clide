package clide.command.navigate;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandPayload;
import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.core.ClideContext;
import clide.core.Command;
import clide.jdtls.JdtlsSession;
import clide.model.Listing;

/**
 * workspace/symbol: finds symbols by name anywhere in the project, without
 * already knowing which file/line they're in - exactly the lookup goto_* itself
 * needs (file path + line + symbol). Matching is entirely jdtls' own (typically
 * fuzzy/camelCase, not exact) - clide applies no filtering of its own on top of
 * it, by design (see CLAUDE.md).
 */
public class FindSymbolCommand extends Command {

	@Keyword("find_symbol")
	@Help("Finds symbols by name anywhere in the project - matching is jdtls' own (typically fuzzy/camelCase, not exact). Use this to locate the <position> that find_declaration/find_reference/find_implementation need - results are printed in that notation, ready to paste.")
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
				result. Meant to locate the <position> that
				find_declaration, find_reference and find_implementation
				all need as input: run find_symbol first, then feed what it
				prints - already a whole <position>, nothing to append -
				into whichever of those actually answers your question.

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
		return SymbolListRendering.render("find_symbol", "symbol",
				subject -> "find_symbol: no symbol found for \"" + subject
						+ "\" - note that find_symbol matches types and methods only, never a field name",
				result);
	}

}
