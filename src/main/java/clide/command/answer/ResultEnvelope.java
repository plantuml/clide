package clide.command.answer;

/**
 * Wraps the body a command rendered (see Command.render()) in the part of the
 * output that is the same for every command - and is therefore written once,
 * here, rather than at each of the places that used to build an error string by
 * hand.
 *
 * The shape a client sees:
 *
 * <pre>
 * ?ERROR LINE_OUT_OF_RANGE: Line 999 out of range (file has 312 line(s)): Foo.java
 * hint: run find_symbol Foo to locate it
 * &lt;body, when an error still has something to show&gt;
 * !WARNING AMBIGUOUS_NAME_ON_LINE: 'foo' appears twice on line 42 - resolved the first
 * </pre>
 *
 * On success there is no header at all: the body is the answer, and prefixing
 * every successful result with a decorative "OK" would be one more thing for a
 * client to strip. The "?" of "?ERROR" is the marker clide has always used for a
 * refused command (it replaces "?SYNTAX ERROR", which said only *that* something
 * was wrong with the input and never *what*); "!" marks a warning, so the two
 * can be told apart on sight and by a regex.
 *
 * One line per fact, and nothing wrapped or padded: the same output feeds a
 * person reading a terminal and a program reading a socket, and the second one
 * is the one that breaks when a message gets folded over two lines.
 */
public final class ResultEnvelope {

	public static final String ERROR_PREFIX = "?ERROR ";
	public static final String WARNING_PREFIX = "!WARNING ";
	public static final String HINT_PREFIX = "hint: ";

	private ResultEnvelope() {
	}

	/**
	 * result's envelope around body. body is what Command.render() produced; it
	 * may be empty, and on an error it is usually empty too - only a command that
	 * failed while still having something to show (run_tests listing its failures)
	 * passes one.
	 */
	public static String render(final CommandResult result, final String body) {
		final StringBuilder out = new StringBuilder();

		if (result.isError()) {
			out.append(ERROR_PREFIX).append(result.code()).append(": ").append(result.message());
			if (result.hasHint())
				out.append('\n').append(HINT_PREFIX).append(result.hint());
		}

		appendBlock(out, body);

		for (final Warning warning : result.warnings())
			appendBlock(out, WARNING_PREFIX + warning.code() + ": " + warning.message());

		return out.toString();
	}

	/**
	 * What a command with no render() of its own says. Covers the two payloads
	 * that need no interpretation; anything else falls back to the record's own
	 * toString(), which is unlovely but honest - and only ever reachable from a
	 * command that has not written its handler yet.
	 */
	public static String defaultBody(final CommandPayload payload) {
		if (payload instanceof CommandPayload.Nothing)
			return "";

		if (payload instanceof CommandPayload.Text text)
			return text.text();

		return payload.toString();
	}

	/**
	 * What a command's render() should return when its own switch reaches a
	 * payload shape none of its cases expected. Not thrown - see ClideDaemon,
	 * whose serve loop catches no RuntimeException around a session and would
	 * take the whole daemon down with it, not just the one connection that hit
	 * the bug.
	 *
	 * CommandPayload.Nothing is not that bug: CommandResult.error() defaults an
	 * ordinary failure's payload to it (see CommandResult), so every command's
	 * switch reaches its default on every error a user causes, not only on a
	 * wrongly-wired one. Nothing therefore still renders as "" here, same as any
	 * other command with nothing to say - only a shape that is neither the one
	 * case a command's switch names nor Nothing means the payload and the render()
	 * reading it have drifted apart, which is always a bug. That shouts, in
	 * English, so it reads as the broken answer it is instead of quietly passing
	 * for an empty one.
	 */
	public static String unexpectedPayload(final String commandName, final CommandPayload payload) {
		if (payload instanceof CommandPayload.Nothing)
			return "";

		return "INTERNAL ERROR: " + commandName + ".render() received a payload it does not know how to render ("
				+ payload.getClass().getSimpleName() + "): " + payload;
	}

	private static void appendBlock(final StringBuilder out, final String block) {
		if (block == null || block.isEmpty())
			return;

		if (out.length() > 0)
			out.append('\n');

		out.append(block);
	}

}
