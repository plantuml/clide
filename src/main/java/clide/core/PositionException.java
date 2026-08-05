package clide.core;

import clide.command.answer.ErrorCode;

/**
 * Why a &lt;file path&gt;:&lt;line&gt;:&lt;column&gt;:&lt;name&gt; token could
 * not be resolved - an IllegalArgumentException, as PositionParser.parse() has always
 * thrown, now carrying the ErrorCode that says which of the ways it failed, and
 * optionally the hint that goes with it.
 *
 * Still an IllegalArgumentException on purpose: every existing catch site keeps
 * working unchanged, and a caller that does not care about the code simply
 * reads getMessage() as before. The message stays fit to send to a client
 * as-is.
 */
public final class PositionException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode code;
	private final String hint;

	public PositionException(final ErrorCode code, final String message) {
		this(code, message, "");
	}

	/**
	 * hint is what CommandResult.error() carries alongside the message - "" for
	 * none, which is the default and the common case (see CODING.md: a hint only
	 * earns its place when it says something the message and the docs do not
	 * already give). NAME_NOT_AT_COLUMN is the one position failure that has one:
	 * the columns the name actually starts at, which the client cannot work out
	 * without re-reading the file.
	 */
	public PositionException(final ErrorCode code, final String message, final String hint) {
		super(message);
		this.code = code;
		this.hint = hint == null ? "" : hint;
	}

	public ErrorCode getCode() {
		return code;
	}

	public String getHint() {
		return hint;
	}

	/**
	 * The code carried by e if it knows one, IO_FAILED otherwise - for the catch
	 * sites that catch the broader IllegalArgumentException and still want to
	 * report something better than a generic failure.
	 */
	public static ErrorCode codeOf(final RuntimeException e) {
		if (e instanceof PositionException position)
			return position.getCode();

		return ErrorCode.IO_FAILED;
	}

	/** The hint carried by e if it knows one, "" otherwise - see codeOf(). */
	public static String hintOf(final RuntimeException e) {
		if (e instanceof PositionException position)
			return position.getHint();

		return "";
	}

}
