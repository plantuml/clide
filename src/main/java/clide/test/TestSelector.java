package clide.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns clide's "path:line:column:name" notation into what TestRunnerMain
 * selects on.
 *
 * The whole point is that the answer of find_symbol can be pasted straight into
 * run_test with no editing - which is the property TESTS.md keeps identifying
 * as the tool's strength. So the input is a Position, not a fully qualified
 * class name the client would have to rebuild by hand from a path.
 *
 * The class name is read off the file: its package declaration plus its own
 * name, which for a Java source file is the name of the type it declares. The
 * symbol's name then decides the granularity - naming the class runs all of it,
 * naming anything else runs that one method.
 */
public final class TestSelector {

	private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

	private TestSelector() {
	}

	/** Reads file to find its package, then delegates to selector(). */
	public static String[] forFile(final Path file, final String symbolName) throws IOException {
		return selector(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString(), symbolName);
	}

	/**
	 * The argument list TestRunnerMain expects: two elements for "run the whole
	 * class", three for "run one name inside it".
	 *
	 * Deliberately does not build a "Class#method" string the way
	 * DiscoverySelectors.selectMethod(String) wants it: that form has no way to
	 * spell a parameter list (so an absent one is read as "takes no arguments",
	 * wrong for every @ParameterizedTest/@RepeatedTest) and no way to spell a
	 * @Nested class (a bare method name typed against the file's own top-level
	 * class is simply the wrong class for anything declared inside one). The
	 * file name is the only thing this method can resolve with certainty -
	 * jdtls already resolved <position> to the real declaration, but that
	 * information does not survive the trip through the "path:line:column:name"
	 * notation - so it always names the top-level class here and leaves
	 * TestRunnerMain.buildRequest() to discover that whole class and filter
	 * down to <symbolName> by its simple name, method or @Nested class alike.
	 *
	 * Pure on purpose - no filesystem, no jdtls - so the naming rules can be
	 * tested for what they are.
	 */
	public static String[] selector(final String source, final String fileName, final String symbolName) {
		final String typeName = typeName(fileName);
		final String qualified = qualify(packageOf(source), typeName);

		if (symbolName.equals(typeName))
			return new String[] { "--class", qualified };

		return new String[] { "--method", qualified, symbolName };
	}

	/** The class a Java source file declares: its name, minus the extension. */
	public static String typeName(final String fileName) {
		final int dot = fileName.lastIndexOf('.');
		return dot < 0 ? fileName : fileName.substring(0, dot);
	}

	/** "" for a file in the default package - never null. */
	public static String packageOf(final String source) {
		final Matcher matcher = PACKAGE.matcher(stripComments(source));
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String qualify(final String packageName, final String typeName) {
		return packageName.isEmpty() ? typeName : packageName + "." + typeName;
	}

	/**
	 * Blanks out comments before looking for the package declaration, so that a
	 * licence header mentioning "package com.example;" - PlantUML's files open on
	 * a forty-line one - does not win over the real declaration below it. Replaces
	 * rather than removes, keeping every character where it was.
	 */
	private static String stripComments(final String source) {
		final StringBuilder out = new StringBuilder(source);
		int i = 0;
		while (i < out.length() - 1) {
			final char c = out.charAt(i);
			final char next = out.charAt(i + 1);
			if (c == '/' && next == '/') {
				while (i < out.length() && out.charAt(i) != '\n')
					out.setCharAt(i++, ' ');

				continue;
			}
			if (c == '/' && next == '*') {
				while (i < out.length() && (i + 1 >= out.length() || out.charAt(i) != '*' || out.charAt(i + 1) != '/')) {
					if (out.charAt(i) != '\n')
						out.setCharAt(i, ' ');

					i++;
				}
				for (int end = 0; end < 2 && i < out.length(); end++)
					out.setCharAt(i++, ' ');

				continue;
			}
			i++;
		}
		return out.toString();
	}

}
