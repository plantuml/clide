package clide.lua;

import java.nio.file.Path;

import clide.annotation.ParamType;
import clide.core.Command;
import clide.core.PositionException;
import clide.core.PositionParser;
import clide.model.Position;
import party.iroiro.luajava.Lua;

/**
 * The arguments a script passed, as the String[] a command expects.
 *
 * Reading the stack is all this does; it never decides whether a value is
 * *acceptable* - CommandDispatcher does, with the same ParamType checks the text
 * protocol runs. What is caught here is the one class of mistake the text
 * protocol cannot make, because a line of text has no types: a script handing
 * over a table where a name was expected, a boolean where a count was, or four
 * arguments to a command that takes two.
 *
 * <b>A position may be a table.</b> Every clide result gives a script positions
 * as {path, line, column, name} tables, and the natural thing to do with one is
 * to pass it straight to the next call - so that is accepted, alongside the
 * "path:line:column:name" string a script may equally well have written itself.
 * The table goes through PositionParser.of(), which runs the very same
 * validation parse() does: a position a script carried across an edit is checked
 * against the file as it stands now, not trusted because it was true when it was
 * produced.
 */
final class LuaArguments {

	private LuaArguments() {
	}

	/**
	 * command.paramSize() strings read off L's stack, in order. Raises a
	 * LuaScriptError - never returns a partial array - if the count is wrong or a
	 * value cannot be read as the parameter it stands for.
	 */
	static String[] read(final Lua lua, final Command command, final Path projectRoot) {
		final ParamType[] types = command.getParamTypes();
		final String[] labels = command.getDescriptionParam();
		final int given = lua.getTop();
		if (given != types.length)
			throw new LuaScriptError(command.getKeyword() + "() expects " + arity(command) + ", got " + given);

		final String[] params = new String[types.length];
		for (int i = 0; i < types.length; i++)
			params[i] = one(lua, i + 1, types[i], labels[i], command.getKeyword(), projectRoot);

		return params;
	}

	private static String one(final Lua lua, final int index, final ParamType type, final String label,
			final String keyword, final Path projectRoot) {
		if (type == ParamType.POSITION && lua.isTable(index))
			return positionFromTable(lua, index, label, keyword, projectRoot);

		if (lua.isString(index) == false && lua.isNumber(index) == false)
			throw new LuaScriptError(keyword + "(): argument " + index + " (<" + label.toLowerCase()
					+ ">) must be a string" + (type == ParamType.POSITION ? " or a position table" : "") + ", got "
					+ lua.type(index).toString().toLowerCase());

		// isString() is true for a number too - Lua coerces the two freely - so a
		// count written 1000 and one written "1000" both arrive here as text, which
		// is what every ParamType check downstream reads.
		return lua.toString(index).trim();
	}

	/**
	 * A {path, line, column, name} table, validated into a Position and written
	 * back out in the notation the command expects. The round trip is deliberate:
	 * the command's own ParamType.POSITION check re-reads it downstream, and going
	 * through the notation is what guarantees a table-built position and a
	 * string-built one are the same thing by the time a command sees either.
	 */
	private static String positionFromTable(final Lua lua, final int index, final String label, final String keyword,
			final Path projectRoot) {
		final String path = field(lua, index, "path", label, keyword);
		final String name = field(lua, index, "name", label, keyword);
		final int line = intField(lua, index, "line", label, keyword);
		final int column = intField(lua, index, "column", label, keyword);

		try {
			final Position position = PositionParser.of(path, line, column, name, projectRoot);
			return position.toString();
		} catch (final PositionException e) {
			throw new LuaScriptError(LuaErrors.text(PositionException.codeOf(e), e.getMessage(), e.getHint()));
		}
	}

	private static String field(final Lua lua, final int index, final String key, final String label,
			final String keyword) {
		lua.getField(index, key);
		try {
			if (lua.isString(-1) == false)
				throw new LuaScriptError(keyword + "(): <" + label.toLowerCase() + "> table has no string '" + key
						+ "' - a position table is {path, line, column, name}, as every clide result gives it");

			return lua.toString(-1).trim();
		} finally {
			lua.pop(1);
		}
	}

	private static int intField(final Lua lua, final int index, final String key, final String label,
			final String keyword) {
		lua.getField(index, key);
		try {
			if (lua.isNumber(-1) == false)
				throw new LuaScriptError(keyword + "(): <" + label.toLowerCase() + "> table has no number '" + key
						+ "' - a position table is {path, line, column, name}, as every clide result gives it");

			final double value = lua.toNumber(-1);
			if (value != Math.floor(value))
				throw new LuaScriptError(keyword + "(): <" + label.toLowerCase() + "> table has a fractional '" + key
						+ "' (" + value + ")");

			return (int) value;
		} finally {
			lua.pop(1);
		}
	}

	private static String arity(final Command command) {
		final int expected = command.paramSize();
		if (expected == 0)
			return "no argument";

		final StringBuilder out = new StringBuilder(expected + " argument" + (expected > 1 ? "s" : "") + " (");
		final String[] labels = command.getDescriptionParam();
		for (int i = 0; i < labels.length; i++) {
			if (i > 0)
				out.append(", ");

			out.append('<').append(labels[i].toLowerCase()).append('>');
		}
		return out.append(')').toString();
	}

}
