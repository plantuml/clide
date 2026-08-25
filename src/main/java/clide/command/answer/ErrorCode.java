package clide.command.answer;

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
	// Position: resolving <file-content-md5>:<file path>:<line>:<column>:<name> against the project
	// ------------------------------------------------------------------

	/**
	 * The token did not even parse as
	 * &lt;file-content-md5&gt;:&lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt;
	 * - including the one near miss worth naming separately in the message: a
	 * &lt;file-content-md5&gt; that is 32 hexadecimal characters but not lowercase.
	 */
	MALFORMED_POSITION,

	/** The notation parsed, but its file path names nothing on disk. */
	FILE_NOT_FOUND,

	/** The file exists but could not be read as UTF-8 text. */
	FILE_UNREADABLE,

	/**
	 * The token carried a &lt;file-content-md5&gt; that is no longer the file's -
	 * the file has been edited since this position was produced, so the position
	 * describes a state of it that is gone. Nothing about the *token* can be
	 * repaired in place - the position has to be produced again against the file
	 * as it now is (see PositionParser.parse()).
	 *
	 * The obvious "repair" - handing back the file's current md5 so the same
	 * token passes next time - is deliberately never done: pasting that back
	 * would produce a token that passes the check while pointing wherever the
	 * edit moved that line to, defeating the very thing FILE_MODIFIED exists to
	 * catch.
	 *
	 * A hint may still appear, and it is a different thing entirely: a complete,
	 * freshly re-derived &lt;position&gt; for the same name, offered only when
	 * clide found real evidence - the name's exact old line, read back unchanged
	 * from a cached historical revision, still exists byte for byte somewhere in
	 * the current file - that it still names the right spot (see
	 * PositionParser.staleHint()). That evidence is often missing (any edit to
	 * the line itself defeats it, and the historical revision is only cached at
	 * all if a rebuild ran while it was live), so most FILE_MODIFIED failures
	 * still carry no hint - but when one appears, it is a genuinely fresh,
	 * already-checked position, not a way around the check.
	 */
	FILE_MODIFIED,

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

	/** The position resolved, but names nothing find_callers/find_callees can work with (e.g. a field or a type). */
	NOT_A_METHOD,

	/**
	 * A Classe::membre, Classe/Outer.Inner, or NomFichier.java token (see
	 * SYMBOLS.md) resolved to zero candidates in the project - no class, member,
	 * or file of that name exists. One code for all three grammars: the caller's
	 * next move is the same in each case, check the spelling or fall back to a
	 * full path.
	 */
	SYMBOL_NOT_FOUND,

	/**
	 * A Classe::membre, Classe/Outer.Inner, or NomFichier.java token (see
	 * SYMBOLS.md) resolved to more than one candidate - the hint lists them. Per
	 * SYMBOLS.md's cardinal principle, clide never silently picks the first
	 * match; the caller must disambiguate, typically by falling back to a full
	 * path or adding an arity to a Classe::methode(N) token.
	 */
	AMBIGUOUS_SYMBOL,

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
	// Modifying commands
	// ------------------------------------------------------------------

	/**
	 * A .java file changed on disk since the last build, so jdtls' model no
	 * longer describes the project a modifying command is about to change - the
	 * message names the files.
	 *
	 * Not the same guarantee as FILE_MODIFIED, and not obtainable from it.
	 * FILE_MODIFIED protects <i>one position</i>, through the md5 the position
	 * carries; this protects <i>the whole project</i>, because a refactoring
	 * reads and rewrites files the caller never named. A reference added to some
	 * other file since the last build is invisible to a per-position check, and
	 * jdtls does not catch it either - it simply would not be renamed, silently.
	 * See RenameCommand.
	 */
	STALE_MODEL,

	/** jdtls will not rename the symbol at this position - a keyword, a literal, something read-only. */
	NOT_RENAMEABLE,

	/** A name a modifying command was given is not a usable Java identifier. */
	INVALID_JAVA_NAME,

	/** The edit jdtls computed could not be applied as given - see EditApplicationException. */
	EDIT_NOT_APPLICABLE,

	/** move_class was given a position that does not name a top-level type declaration. */
	NOT_A_TOP_LEVEL_TYPE,

	/**
	 * move_class targets a file that declares more than one top-level type - moving
	 * it would silently drag every other type in the file along with it, so the
	 * command refuses and names the other type(s) found in the file instead.
	 */
	MULTIPLE_TOP_LEVEL_TYPES,

	/** The package name move_class was given is not a sequence of valid Java identifiers. */
	INVALID_JAVA_PACKAGE_NAME,

	/** move_class's destination path is already occupied by another file. */
	DESTINATION_FILE_EXISTS,

	/**
	 * The file's own path on disk does not match the package it declares, so
	 * move_class cannot reliably compute where it belongs after the move.
	 */
	PACKAGE_DIRECTORY_MISMATCH,

	/**
	 * move_class refuses to run against a project that does not already
	 * compile - print_diagnostics shows what. Without this, a non-zero error
	 * count in move_class's own answer could not be told apart from an error
	 * the move introduced: this is the only way to make that count mean
	 * "caused by this move" rather than "present somewhere in the project".
	 */
	PROJECT_HAS_ERRORS,

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
	// remove_unused_imports
	// ------------------------------------------------------------------

	/**
	 * &lt;path regex&gt; matched no file under the project - the message names
	 * the regex. Distinct from "matched files, none had an unused import":
	 * that second case is not an error at all, see remove_unused_imports.
	 */
	NO_FILES_FOUND,

	// ------------------------------------------------------------------
	// Misc
	// ------------------------------------------------------------------

	/** An I/O failure with no more specific code - the message carries the detail. */
	IO_FAILED,

	// ------------------------------------------------------------------
	// Lua
	// ------------------------------------------------------------------

	/**
	 * A Lua script did not run to its end: a syntax error, an error one of the
	 * bound clide functions raised and the script never caught, or one the script
	 * raised itself. The message is Lua's own, which names the line - see
	 * LuaBridge.
	 */
	LUA_SCRIPT_FAILED
}
