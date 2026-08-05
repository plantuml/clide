package clide.command.answer;

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
	 * exit/quit with transactions still open. They survive, untouched, for the
	 * next connection to commit or roll back - purely informational, nothing is
	 * blocked by it.
	 */
	TRANSACTIONS_STILL_OPEN
}
