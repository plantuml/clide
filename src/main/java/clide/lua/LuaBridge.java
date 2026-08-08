package clide.lua;

import java.io.PrintStream;

import clide.command.answer.CommandResult;
import clide.command.answer.ErrorCode;
import clide.command.answer.ResultEnvelope;
import clide.command.answer.Warning;
import clide.core.ClideContext;
import clide.core.Command;
import clide.core.CommandDispatcher;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua51.Lua51;

/**
 * Runs one Lua script against one project, with every clide command bound as a
 * function of the same name.
 *
 * <b>In the daemon, not in the client.</b> The functions bound here call
 * commands directly, so they need what only the daemon has: the jdtls session
 * that answers the questions, the transaction stack, the project root every
 * relative path resolves against - the whole ClideContext. A script run in the
 * client process could only have talked to the daemon through the text
 * protocol, and would have had to parse clide's own pretty-printed output back
 * into values - which is precisely the work CommandPayload exists to make
 * unnecessary.
 *
 * <b>One function per command, generated, not written.</b> The name is the
 * command's @Keyword, the arity its @Param count, the argument checks its
 * ParamTypes - all read by reflection off the command itself (see Command), the
 * same metadata help and man are built from. A command added to
 * CommandRepository is callable from Lua with nothing written here, as long as
 * its payload has a case in LuaPayloads.
 *
 * <b>Nothing bypasses the guards.</b> Every call goes through
 * CommandDispatcher, the same entry point the text protocol uses, so a script
 * gets the same parameter validation, the same "requires an open transaction"
 * refusal and the same lazily-restarted jdtls session. Calling
 * executeCommand() straight from here would have been shorter and would have
 * handed scripts a way around all three.
 *
 * <b>print is replaced.</b> Lua's own print writes to the process' native
 * stdout, which in the daemon is a log file nobody is watching - a script would
 * run correctly and appear to say nothing. The binding below writes to the
 * client's socket instead, which is where its author is looking.
 */
public final class LuaBridge {

	private final ClideContext context;
	private final PrintStream out;

	public LuaBridge(final ClideContext context, final PrintStream out) {
		this.context = context;
		this.out = out;
	}

	/**
	 * Runs script to its end, or reports why it stopped. Everything the script
	 * printed has already been written to out by then; a failure is written after
	 * it, in the same "?ERROR" envelope a refused command uses, so a client tells
	 * the two apart the way it always has.
	 *
	 * Returns true when the script ran to the end.
	 */
	public boolean run(final String script) {
		try (Lua lua = new Lua51()) {
			bind(lua);
			lua.run(script);
			return true;
		} catch (final LuaException e) {
			// Lua's own message, which names the line for a syntax or runtime error;
			// for an error raised by one of the bound functions it is that function's
			// "?ERROR <CODE>: ..." text, already fit to print (see LuaScriptError).
			out.println(ResultEnvelope.ERROR_PREFIX + ErrorCode.LUA_SCRIPT_FAILED + ": " + e.getMessage());
			return false;
		}
	}

	/**
	 * The Lua libraries a script gets, and the only ones. base, string, table and
	 * math are what writing a filter or a count takes - the four the first
	 * scripts are made of.
	 *
	 * io, os, package and debug are left out, deliberately: a script runs inside
	 * the daemon, with its file access and its process. Everything a clide script
	 * has to reach, it reaches through a clide command, which validates its
	 * arguments and backs up what it touches - an io.open() next to those would
	 * be a way around every guarantee they make. Opening one later is a decision
	 * to take on its own; opening all of them by calling openLibraries() would be
	 * that decision taken by accident.
	 */
	private static final String[] LIBRARIES = { "base", "string", "table", "math" };

	private void bind(final Lua lua) {
		for (final String library : LIBRARIES)
			lua.openLibrary(library);

		for (final Command command : context.getAllCommands())
			if (command.isScriptable())
				bind(lua, command);

		// After the libraries, not before: base brings its own print, and this one
		// has to be the one that wins - see printFunction().
		lua.push(printFunction());
		lua.setGlobal("print");
	}

	private void bind(final Lua lua, final Command command) {
		lua.push((JFunction) state -> call(state, command));
		lua.setGlobal(command.getKeyword());
	}

	/**
	 * One bound command, called from Lua: read the arguments, dispatch, hand back
	 * the payload as a table.
	 *
	 * A refused command raises rather than returning something to test. That is
	 * the Lua convention (and pcall is right there for a script that wants to
	 * handle it), and it is the safer default for a script that writes: an
	 * unchecked failure stops the script instead of letting it carry on believing
	 * the edit happened.
	 *
	 * Every Java throwable is turned into a Lua error, none is allowed to escape:
	 * the caller here is native code, which has no idea what to do with one.
	 */
	private int call(final Lua lua, final Command command) {
		try {
			final String[] params = LuaArguments.read(context.getFilesRepository(), lua, command);
			final CommandResult result = CommandDispatcher.dispatch(context, command, out::println, params);

			// A warning does not stop anything - it is printed where the script's own
			// output goes, exactly as the text protocol prints it alongside the answer.
			for (final Warning warning : result.warnings())
				out.println(ResultEnvelope.WARNING_PREFIX + warning.code() + ": " + warning.message());

			if (result.isError())
				return lua.error(new LuaScriptError(LuaErrors.text(result)));

			lua.push(LuaPayloads.toLua(result.payload()), Lua.Conversion.FULL);
			return 1;
		} catch (final LuaScriptError e) {
			return lua.error(e);
		} catch (final RuntimeException e) {
			// A bug in clide, not in the script - said as such, rather than reaching
			// the script disguised as an ordinary refusal it might try to handle.
			return lua.error(new LuaScriptError("INTERNAL ERROR in " + command.getKeyword() + "(): " + e));
		}
	}

	/**
	 * print(...), rewritten to reach the client. Same contract as Lua's own: every
	 * argument converted to text, separated by tabs, one line. Values are
	 * converted here rather than through Lua's tostring so that a table prints as
	 * "table" instead of an address a reader can do nothing with.
	 */
	private JFunction printFunction() {
		return lua -> {
			final int count = lua.getTop();
			final StringBuilder line = new StringBuilder();
			for (int i = 1; i <= count; i++) {
				if (i > 1)
					line.append('\t');

				line.append(text(lua, i));
			}
			out.println(line);
			return 0;
		};
	}

	private String text(final Lua lua, final int index) {
		if (lua.isNoneOrNil(index))
			return "nil";

		if (lua.isBoolean(index))
			return Boolean.toString(lua.toBoolean(index));

		final String value = lua.toString(index);
		return value == null ? lua.type(index).toString().toLowerCase() : value;
	}

}
