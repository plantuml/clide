package clide.core;

import java.lang.reflect.Constructor;

import clide.PrintMode;
import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;
import clide.annotation.ParamType;
import clide.command.answer.CommandResult;
import clide.command.answer.ResultEnvelope;

/**
 * A single clide command: identified by a @Keyword, documented by @Help,
 * declaring its expected parameters via (repeatable) @Param, all carried on its
 * public no-arg constructor. Metadata is read via reflection off that
 * constructor - it is never invoked, only inspected - so a command stays a
 * plain, stateless, no-arg-constructible class while still describing itself.
 *
 * Convention followed by every command, so help output and the interactive
 * prompts (see Main.readParams, which prints each @Param value verbatim as "&gt;
 * &lt;value&gt; ?") stay consistent as commands are added:
 * <ul>
 * <li>Annotation order on the constructor: @Keyword, then @Help, then one
 *
 * @Param per expected parameter, in declaration order.</li>
 *        <li>@Help is one verb-first sentence ending with a period. A free-text
 *        parameter is referenced as &lt;label&gt;, label being its @Param value
 *        in lower case (@Param("Project path") to "&lt;project path&gt;"). A
 *        parameter restricted to a fixed set of literal values instead spells
 *        out each one in its own &lt;literal&gt;, e.g. "&lt;all&gt; ...,
 *        &lt;errors&gt; ...".</li>
 *        <li>@Param is a short, sentence-case label (capitalize only the first
 *        word). Literal values the user must type verbatim (e.g. "errors") stay
 *        lower case even inside an otherwise capitalized label.</li>
 *        </ul>
 *
 * Comparable by @Keyword, alphabetically - lets callers (e.g. HelpCommand)
 * list commands via Collections.sort() instead of sorting by hand.
 */
public abstract class Command implements Comparable<Command> {

	/**
	 * Runs this command. context carries state shared across commands (the jdtls
	 * session, whether this connection/the daemon should stop). params.length
	 * always equals paramSize().
	 */
	public abstract CommandResult executeCommand(ClideContext context, String... params);

	/**
	 * Turns what this command found into the text a client reads - its handler,
	 * and the only place that decides how this command's results look.
	 *
	 * Kept on the command rather than in a registry keyed by keyword, for the same
	 * reason @Keyword/@Help/@Param/@Manual are: a command already describes
	 * itself, and a parallel table indexed by name is one more thing that can
	 * drift out of step with the command it describes, for no gain. Here the
	 * producer of a payload and its reader are the same class, and the sealed
	 * CommandPayload hierarchy means reading a shape the command never produces
	 * does not compile.
	 *
	 * Returns the <b>body</b> only. The error header, the hint and the warning
	 * lines are identical for every command and are added around this by
	 * ResultEnvelope - see ClideDaemon.printResult(). Returning "" is normal: a
	 * command with nothing to say says nothing.
	 *
	 * printMode is passed because a few commands legitimately read differently for
	 * a person and for a program (help is the one that does today). Most ignore it
	 * - one rendering that needs no stripping beats two that can disagree.
	 *
	 * The default handles the payloads that need no interpretation (Nothing,
	 * Text) and falls back to the record's own toString() for anything else -
	 * only reachable from a command that has not written its handler yet.
	 */
	public String render(final CommandResult result, final PrintMode printMode) {
		return ResultEnvelope.defaultBody(result.payload());
	}

	/**
	 * Whether this command needs the project's jdtls session to be running.
	 * Defaults to true; commands that never touch jdtls (help, exit/quit/
	 * terminate, search_regex) override this to false. ClideDaemon only pays the
	 * cost of lazily restarting a session previously stopped by "exit"/"quit" for
	 * commands that actually declare they need it - see
	 * ClideDaemon.ensureSessionReady().
	 */
	public boolean needsJdtlsSession() {
		return true;
	}

	/**
	 * Whether this command needs at least one transaction currently open before
	 * it's allowed to run - see TransactionStack, CLAUDE.md. Defaults to false;
	 * only commands that modify project files are expected to override it to
	 * true (none of the transaction commands themselves do - open_transaction is
	 * exactly how the first one gets opened). ClideDaemon checks this before
	 * executeCommand() ever runs - see ClideDaemon.runSession().
	 */
	public boolean needsOpenTransaction() {
		return false;
	}

	/**
	 * Whether this command is exposed to Lua scripts as a function of the same
	 * name - see LuaBridge. Defaults to true: a command that answers a question
	 * about the project answers it just as well from a script.
	 *
	 * The three that override it to false control the connection rather than the
	 * project. "exit"/"quit" would stop the jdtls session in the middle of the
	 * script still querying it, and "terminate" would shut down the very daemon
	 * running that script. A script ends by ending; it has nothing to disconnect
	 * from.
	 */
	public boolean isScriptable() {
		return true;
	}

	public String getKeyword() {
		final Constructor<?> ctor = noArgConstructor();
		if (ctor == null)
			return null;

		final Keyword keyword = ctor.getAnnotation(Keyword.class);
		return keyword == null ? null : keyword.value();
	}

	public String getHelp() {
		final Constructor<?> ctor = noArgConstructor();
		if (ctor == null)
			return "";

		final Help help = ctor.getAnnotation(Help.class);
		return help == null ? "" : help.value();
	}

	public String getManual() {
		final Constructor<?> ctor = noArgConstructor();
		if (ctor == null)
			return "";

		final Manual man = ctor.getAnnotation(Manual.class);
		return man == null ? "" : man.value();
	}

	public String[] getDescriptionParam() {
		final Constructor<?> ctor = noArgConstructor();
		if (ctor == null)
			return new String[0];

		final Param[] params = ctor.getAnnotationsByType(Param.class);
		final String[] descriptions = new String[params.length];
		for (int i = 0; i < params.length; i++)
			descriptions[i] = params[i].description();

		return descriptions;
	}

	/**
	 * Same order/length as getDescriptionParam() - the ParamType each parameter
	 * expects, used by ClideDaemon to run each one's surface check (see
	 * ParamType, ClideDaemon.validate()) before this command ever runs.
	 */
	public ParamType[] getParamTypes() {
		final Constructor<?> ctor = noArgConstructor();
		if (ctor == null)
			return new ParamType[0];

		final Param[] params = ctor.getAnnotationsByType(Param.class);
		final ParamType[] types = new ParamType[params.length];
		for (int i = 0; i < params.length; i++)
			types[i] = params[i].type();

		return types;
	}

	public int paramSize() {
		return getDescriptionParam().length;
	}

	/** Alphabetical order on @Keyword. */
	@Override
	public int compareTo(final Command other) {
		return getKeyword().compareTo(other.getKeyword());
	}

	private Constructor<?> noArgConstructor() {
		try {
			return this.getClass().getConstructor();
		} catch (final NoSuchMethodException e) {
			return null;
		}
	}

}
