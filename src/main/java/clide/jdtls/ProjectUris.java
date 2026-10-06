package clide.jdtls;

import java.net.URI;

/**
 * Tells whether a file: URI that jdtls sent names a file of the project, and
 * which one - by what the URI stands for, not by its text.
 *
 * The same Windows file is spelled file:///C:/x, file:///c:/x, file:///c%3A/x,
 * file:/C:/x or file://C:/x, depending on who built the URI. clide's own URIs
 * (Path.toUri()) have one spelling; the ones jdtls builds on its own - the
 * locations of textDocument/references, workspace/symbol, callHierarchy... - can
 * have another, and a plain startsWith() against the project's own URI then
 * drops every one of them as "outside the project": every method looks unused,
 * find_symbol returns a symbol without a location.
 *
 * Both URIs are reduced to the same form first (see pathOf()): decoded,
 * '/'-separated, drive letter in lower case. A path that starts with a drive
 * letter is compared without regard to case, as Windows does; any other is
 * compared exactly.
 */
final class ProjectUris {

	private ProjectUris() {
	}

	/**
	 * The path the URI stands for, as "/home/x/y" or "c:/users/x" (decoded, '/'
	 * separated, drive letter in lower case, no trailing '/'), or null when it is
	 * not a file: URI.
	 */
	static String pathOf(final String uri) {
		if (uri == null || uri.startsWith("file:") == false)
			return null;

		String path;
		try {
			// getSchemeSpecificPart() is decoded (%3A is ':'); "file://C:/x" keeps its "//C:" in it.
			path = URI.create(uri).getSchemeSpecificPart();
		} catch (final IllegalArgumentException e) {
			path = uri.substring("file:".length());
		}
		if (path == null)
			return null;

		path = path.replace('\\', '/');
		int start = 0;
		while (start < path.length() && path.charAt(start) == '/')
			start++;
		path = path.substring(start);

		final boolean drive = path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
				&& (path.length() == 2 || path.charAt(2) == '/');
		path = drive ? Character.toLowerCase(path.charAt(0)) + path.substring(1) : "/" + path;

		while (path.length() > 1 && path.endsWith("/"))
			path = path.substring(0, path.length() - 1);
		return path;
	}

	/** Whether uri is the project root or a file below it. */
	static boolean isInside(final String projectUri, final String uri) {
		return relativePath(projectUri, uri) != null;
	}

	/**
	 * The path of uri relative to the project root, '/'-separated ("" for the root
	 * itself), as spelled in uri; null when uri is not inside the project.
	 */
	static String relativePath(final String projectUri, final String uri) {
		final String root = pathOf(projectUri);
		final String path = pathOf(uri);
		if (root == null || path == null || path.length() < root.length())
			return null;

		final boolean windows = isDrivePath(root);
		if (path.regionMatches(windows, 0, root, 0, root.length()) == false)
			return null;

		if (path.length() == root.length())
			return "";

		// "/a/bc" is not inside "/a/b": the root must end at a separator.
		if (path.charAt(root.length()) != '/')
			return null;

		return path.substring(root.length() + 1);
	}

	private static boolean isDrivePath(final String path) {
		return path.length() >= 2 && path.charAt(1) == ':';
	}
}
