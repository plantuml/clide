package clide.result;

/**
 * Why a command refused to answer - the machine-readable half of an ERROR
 * CommandResult, printed by CommandRendering as "?ERROR &lt;CODE&gt;: message".
 *
 * Exists so that a caller (a test, a future JSON mode, an agent reading the
 * text) can branch on the *kind* of failure without matching on a message
 * string, and so that messages stay free to be reworded. The rule for adding a
 * value: a new code must name a failure a caller would plausibly react to
 * differently. Two failures that always call for the same next move share one
 * code rather than getting one each - a taxonomy finer than clide can actually
 * tell apart is a lie, not a service.
 *
 * Note one code deliberately absent: nothing here reports "the model jdtls
 * answers from predates your edits". clide does not detect that today (only
 * rebuild refreshes the model, on explicit demand - see CLAUDE.md), and a code
 * that is never raised would advertise a guarantee that does not exist. It
 * belongs here the day the check exists, not before.
 */
public enum ErrorCode {

	/** No error - the only code an OK result is allowed to carry. */
	NONE,

	// ------------------------------------------------------------------
	// Protocol: what the client sent, before any command ran
	// ------------------------------------------------------------------

	/** First line named no registered command - see the one-token-per-line protocol. */
	UNKNOWN_KEYWORD,

	/** The client's input ended before the command had all of its parameters. */
	MISSING_PARAMETERS,

	// ------------------------------------------------------------------
	// Parameters: surface checks, run before the command executes
	// ------------------------------------------------------------------

	/** A parameter restricted to a fixed set of literals got something else. */
	INVALID_ENUM_VALUE,

	/** A parameter that must not be empty was empty. */
	EMPTY_PARAMETER,

	/** A ParamType.REGEX parameter did not compile. */
	INVALID_REGEX,

	/** A ParamType.NON_NEGATIVE_INTEGER parameter was not a number, or was negative. */
	INVALID_INTEGER,

	/** A value outside the range clide accepts for that parameter - the message names the bound. */
	VALUE_OUT_OF_RANGE,

	/** A ParamType.TRANSACTION_ID parameter did not match $segment[$segment...]. */
	INVALID_TRANSACTION_ID,

	/** A path expected to be a directory was not one. */
	NOT_A_DIRECTORY,

	// ------------------------------------------------------------------
	// Position: resolving <file path>:<line>:<column>:<name> against the project
	// ------------------------------------------------------------------

	/** The token did not even parse as &lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;. */
	MALFORMED_POSITION,

	/** The notation parsed, but its file path names nothing on disk. */
	FILE_NOT_FOUND,

	/** The file exists but could not be read as UTF-8 text. */
	FILE_UNREADABLE,

	/** The file exists, the line does not - the message names how many lines it has. */
	LINE_OUT_OF_RANGE,

	/**
	 * The line exists but does not carry that name as a whole word anywhere. The
	 * line - or the file - is not the one the caller meant: a stale position, or a
	 * wrong one. Correcting only the column would not help, which is what
	 * separates this from NAME_NOT_AT_COLUMN.
	 */
	NAME_NOT_ON_LINE,

	/**
	 * The name is on the line, as a whole word, just not starting at the column
	 * given - the file has most likely been edited since the position was printed.
	 * Only the column is wrong; the hint names every column the name does start
	 * at, so the token can be fixed without reading the file again.
	 */
	NAME_NOT_AT_COLUMN,

	/** The position resolved, but names nothing this command can work with (e.g. list_members on a method). */
	NOT_A_TYPE,

	// ------------------------------------------------------------------
	// jdtls
	// ------------------------------------------------------------------

	/** The jdtls session could not be (re)started - see ClideDaemon.ensureSessionReady(). */
	SESSION_START_FAILED,

	/** A request reached jdtls and came back an error, or never came back at all. */
	JDTLS_REQUEST_FAILED,

	/** The build itself failed, as opposed to succeeding while reporting compile errors. */
	BUILD_FAILED,

	// ------------------------------------------------------------------
	// Transactions
	// ------------------------------------------------------------------

	/** A file-modifying command ran with no transaction open. */
	NO_OPEN_TRANSACTION,

	/**
	 * The transaction stack refused the operation: id not open, already open,
	 * not extending the current one, or naming a file it never touched. One code
	 * for all of those on purpose - TransactionStack reports them as one kind of
	 * IllegalArgumentException, and inventing four codes clide cannot actually
	 * distinguish would be inventing precision.
	 */
	TRANSACTION_REFUSED,

	/** Reading or writing the transaction's own backups failed. */
	TRANSACTION_IO_FAILED,

	/** terminate, with a transaction still open - the message names them. */
	TERMINATE_REFUSED,

	// ------------------------------------------------------------------
	// Tests
	// ------------------------------------------------------------------

	/** The run completed and some tests failed - the payload carries which ones. */
	TEST_FAILURES,

	/** Nothing at all was selected to run: far more often a wrong selector than a project with no tests. */
	NO_TEST_FOUND,

	/** The named class is not in the compiled output - written or renamed since the last build. */
	TEST_CLASS_NOT_COMPILED,

	/** The forked test JVM could not be started, or died without reporting. */
	TEST_RUNNER_BROKEN,

	/** The run was killed for exceeding its time budget - not a test failure. */
	TEST_TIMEOUT,

	/** jdtls reports no compiled output folder to scan for tests. */
	NO_OUTPUT_FOLDER,

	/** jdtls' classpath for the project could not be read. */
	CLASSPATH_UNAVAILABLE,

	/** The repository holds several modules and clide cannot yet be told which one to use. */
	MULTI_MODULE_PROJECT,

	// ------------------------------------------------------------------
	// Misc
	// ------------------------------------------------------------------

	/** An I/O failure with no more specific code - the message carries the detail. */
	IO_FAILED
}
