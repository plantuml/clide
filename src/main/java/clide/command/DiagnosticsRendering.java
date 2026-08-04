package clide.command;

import clide.result.Diagnostic;
import clide.result.DiagnosticsReport;
import clide.result.Listing;

/**
 * How a DiagnosticsReport reads. Shared by print_diagnostics and rebuild -
 * which ask the same question and differ only in whether they compile first, so
 * their answers have no business looking different.
 *
 * The shape, unchanged from what clide has always printed: one "path:" header
 * per file, its diagnostics indented under it, and a closing "jdtls:" tally.
 */
final class DiagnosticsRendering {

	private DiagnosticsRendering() {
	}

	static String render(final DiagnosticsReport report) {
		if (report.tracked() == false)
			return "jdtls: no diagnostics (project not recognized, or nothing to report)";

		final StringBuilder out = new StringBuilder();
		final Listing<Diagnostic> diagnostics = report.diagnostics();
		String currentFile = null;
		for (final Diagnostic diagnostic : diagnostics.items()) {
			if (diagnostic.path().equals(currentFile) == false) {
				currentFile = diagnostic.path();
				out.append(currentFile).append(":\n");
			}
			out.append("  ").append(diagnostic.display()).append('\n');
		}

		out.append(tally(report));
		if (diagnostics.truncated())
			out.append("\njdtls: ").append(diagnostics.summarize("diagnostic"));

		return out.toString();
	}

	/**
	 * Counted over every diagnostic of the build, never over the excerpt being
	 * shown - so "errors" filtering the listing down, or max_results capping it,
	 * does not quietly change what the tally claims about the project. Which is
	 * also why the truncation notice above is a separate line: the two numbers
	 * count different things and merging them would invite reading one as the
	 * other.
	 */
	private static String tally(final DiagnosticsReport report) {
		if (report.isClean())
			return "jdtls: " + report.fileCount() + " file(s) with tracked diagnostics, no errors or warnings";

		return "jdtls: " + report.errorCount() + " error(s), " + report.warningCount() + " warning(s) in "
				+ report.fileCount() + " file(s)";
	}

}
