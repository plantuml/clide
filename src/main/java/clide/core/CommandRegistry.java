package clide.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Looks up a Command by its @Keyword, built once at startup from a fixed list of commands. */
public class CommandRegistry {

	private final Map<String, Command> byKeyword = new LinkedHashMap<>();

	public CommandRegistry(final List<Command> commands) {
		for (final Command command : commands) {
			final String keyword = command.getKeyword();
			if (keyword == null)
				throw new IllegalStateException(
						command.getClass().getName() + " has no @Keyword on its no-arg constructor");
			if (byKeyword.containsKey(keyword))
				throw new IllegalStateException("Duplicate @Keyword \"" + keyword + "\": "
						+ byKeyword.get(keyword).getClass().getName() + " and " + command.getClass().getName());

			byKeyword.put(keyword, command);
		}
	}

	/** Returns the command registered for this keyword, or null if there is none. */
	public Command find(final String keyword) {
		return byKeyword.get(keyword);
	}

}
