package clide.jdtls;

import java.io.IOException;

/**
 * Raised by JdtlsSession's call/type hierarchy methods (findCallers(),
 * findCallees(), findSupertypes(), findSubtypes()) when the prepare step
 * (textDocument/prepareCallHierarchy or textDocument/prepareTypeHierarchy)
 * comes back empty - jdtls' own way of saying the position given does not
 * name a method/constructor (for the call hierarchy pair) or a
 * class/interface/enum (for the type hierarchy pair) at all, as opposed to
 * naming one that genuinely has no caller/callee/supertype/subtype to
 * report.
 *
 * A dedicated type, not a plain IOException, specifically so the two cases
 * are not conflated at the call site: FindCallersCommand and friends catch
 * this one first, and map it to ErrorCode.NOT_A_METHOD/NOT_A_TYPE - a
 * genuine "wrong kind of symbol" refusal - while every other IOException
 * out of the same methods (the prepare/incomingCalls/outgoingCalls/
 * supertypes/subtypes request itself failing) still falls through to
 * JDTLS_REQUEST_FAILED, exactly as it does for every other command. Without
 * this split, a real jdtls protocol failure would have been misreported as
 * "not a method", pointing the caller at the wrong fix.
 */
public final class NotApplicableException extends IOException {

	private static final long serialVersionUID = 1L;

	public NotApplicableException(final String message) {
		super(message);
	}

}
