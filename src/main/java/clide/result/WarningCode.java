package clide.result;

/**
 * Why a result that clide *did* answer is worth a second look anyway - the
 * machine-readable half of a Warning, printed by CommandRendering as
 * "!WARNING &lt;CODE&gt;: message" after the answer itself.
 *
 * A warning never changes a result's status: the answer stands, and the client
 * is free to ignore the warning entirely. Same rule as ErrorCode for adding a
 * value - a code earns its place by naming something a caller would act on.
 */
public enum WarningCode {

	/**
	 * The name of a &lt;position&gt; appears more than once as a whole word on
	 * its line, and clide resolved the first occurrence - the message names every
	 * column it found. Not an error: resolving the first match is what makes a
	 * result of one command paste straight into the next, and refusing here would
	 * break that chaining for a case that is usually harmless (a.foo(b.foo())).
	 * But it *is* the one case where clide may silently have answered about a
	 * different symbol than the one meant, so it says so.
	 */
	AMBIGUOUS_NAME_ON_LINE,

	/**
	 * exit/quit with transactions still open. They survive, untouched, for the
	 * next connection to commit or roll back - purely informational, nothing is
	 * blocked by it.
	 */
	TRANSACTIONS_STILL_OPEN
}
