package clide.model;

/**
 * One workspace/symbol hit for a class/interface/enum, paired with its
 * declaring scope - LSP's own containerName: the enclosing type's fully
 * qualified name for a nested class ("clide.json.Json" for Json.Parser), or
 * just the package for a top-level one ("clide.json" for Json itself).
 *
 * Exists for exactly one consumer: PositionParser's resolution of the
 * SYMBOLS.md "Classe"/"Outer.Inner seule" and "Classe::membre" notations,
 * which needs containerName to tell two same-named classes in different
 * scopes apart - something CodeLocation alone cannot do, and something
 * find_symbol's own SymbolHit was never asked to carry, since no existing
 * command disambiguates by enclosing scope. See
 * JdtlsSession.findTypesNamed().
 */
public record TypeCandidate(String containerName, CodeLocation location) {

	public TypeCandidate {
		if (containerName == null)
			throw new IllegalArgumentException("containerName must not be null - use \"\" when jdtls gave none");

		if (location == null)
			throw new IllegalArgumentException("location must not be null");
	}

}
