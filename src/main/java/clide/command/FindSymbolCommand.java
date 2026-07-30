package clide.command;

import java.util.List;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandResult;
import clide.jdtls.JdtlsSession;

/**
 * workspace/symbol: finds symbols by name anywhere in the project, without
 * already knowing which file/line they're in - exactly the lookup goto_* itself
 * needs (file path + line + symbol). Matching is entirely jdtls' own (typically
 * fuzzy/camelCase, not exact) - clide applies no filtering of its own on top of
 * it, by design (see CLAUDE.md).
 */
public class FindSymbolCommand extends Command {

	@Keyword("find_symbol")
	@Help("Finds symbols by name anywhere in the project - matching is jdtls' own (typically fuzzy/camelCase, not exact). Use this to locate the <file path>/<line> that find_declaration/find_reference/find_implementation need.")
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
				result. Meant to locate the <file path>/<line> that
				find_declaration, find_reference and find_implementation
				all need as input: run find_symbol first, then feed what it
				finds into whichever of those actually answers your
				question.

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
			return CommandResult.error("Usage: find_symbol <name>");

		try {
			final List<String> symbols = session.findSymbol(query);
			if (symbols.isEmpty())
				return CommandResult.ok("find_symbol: no symbol found for \"" + query + "\"");

			final StringBuilder output = new StringBuilder();
			output.append("find_symbol: ").append(symbols.size()).append(" symbol(s)\n");
			for (final String symbol : symbols)
				output.append(symbol).append('\n');

			return CommandResult.ok(output.toString().strip());
		} catch (final Exception e) {
			return CommandResult.error("find_symbol failed: " + e.getMessage());
		}
	}

}
