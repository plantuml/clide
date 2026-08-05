package clide.command.result;

import java.util.List;

/**
 * One line of help: a command's keyword, the labels of the parameters it
 * expects in order, and its one-line description - all read off the command's
 * own @Keyword/@Param/@Help (see Command), never written twice.
 */
public record CommandSummary(String keyword, List<String> parameters, String help) {

	public CommandSummary {
		if (keyword == null || keyword.isEmpty())
			throw new IllegalArgumentException("keyword must not be empty");

		if (parameters == null)
			throw new IllegalArgumentException("parameters must not be null - use List.of()");

		if (help == null)
			throw new IllegalArgumentException("help must not be null");

		parameters = List.copyOf(parameters);
	}

	/** "&lt;what&gt; &lt;position&gt;", the parameters as help prints them. */
	public String parametersDisplay() {
		final StringBuilder out = new StringBuilder();
		for (final String parameter : parameters) {
			if (out.length() > 0)
				out.append(' ');

			out.append('<').append(parameter).append('>');
		}
		return out.toString();
	}

}
