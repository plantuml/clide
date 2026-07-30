package clide.core;

import java.lang.reflect.Constructor;

import clide.annotation.Help;
import clide.annotation.Keyword;
import clide.annotation.Manual;
import clide.annotation.Param;

/**
 * A single clide command: identified by a @Keyword, documented by @Help,
 * declaring its expected parameters via (repeatable) @Param, all carried on its
 * public no-arg constructor. Metadata is read via reflection off that
 * constructor - it is never invoked, only inspected - so a command stays a
 * plain, stateless, no-arg-constructible class while still describing itself.
 *
 * Convention followed by every command, so help output and the interactive
 * prompts (see Main.readParams, which prints each @Param value verbatim as ">
 * <value> ?") stay consistent as commands are added:
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
