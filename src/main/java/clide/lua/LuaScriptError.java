package clide.lua;

/**
 * The error a bound clide function raises into Lua - a refused command, or an
 * argument that was never the right shape to begin with.
 *
 * A RuntimeException because that is what luajava's Lua.error(Throwable) takes,
 * and one whose toString() is nothing but the message because that string is
 * what a script sees: uncaught it ends up in the "?ERROR LUA_SCRIPT_FAILED"
 * line, and caught it is the second value of a pcall(). Left to
 * RuntimeException's own toString(), every one of them would have arrived
 * prefixed with "clide.lua.LuaScriptError:", a Java package name in the middle
 * of a message written for whoever asked the question.
 *
 * The message itself is already the "?ERROR &lt;CODE&gt;: &lt;message&gt;" shape
 * of the text protocol (see ResultEnvelope), hint included when there is one -
 * one vocabulary of failure for both façades, so a script branching on a code
 * branches on the same codes a client reading text would.
 */
public final class LuaScriptError extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public LuaScriptError(final String message) {
		super(message);
	}

	@Override
	public String toString() {
		return getMessage();
	}

}
