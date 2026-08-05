package clide.model;

/**
 * What the last build had to say about the project: the diagnostics themselves
 * (capped - see Listing) plus the counts they were tallied from.
 *
 * The counts are computed over <b>every</b> diagnostic, before any filtering or
 * capping: "3 error(s), 12 warning(s)" describes the project, not the excerpt
 * being shown. errorsOnly records whether the listing was filtered down to
 * errors, so a handler can say so rather than leaving a reader to wonder why the
 * warnings it just counted are nowhere to be seen.
 *
 * tracked is false when jdtls holds no diagnostics at all for the project -
 * which means "nothing was analyzed", a different statement from "analyzed and
 * clean" and one worth not blurring into it.
 */
public record DiagnosticsReport(Listing<Diagnostic> diagnostics, int errorCount, int warningCount, int fileCount,
		boolean errorsOnly, boolean tracked) {

	public DiagnosticsReport {
		if (diagnostics == null)
			throw new IllegalArgumentException("diagnostics must not be null");

		if (errorCount < 0 || warningCount < 0 || fileCount < 0)
			throw new IllegalArgumentException("counts must not be negative");
	}

	public static DiagnosticsReport untracked() {
		return new DiagnosticsReport(Listing.empty(), 0, 0, 0, false, false);
	}

	public boolean isClean() {
		return errorCount == 0 && warningCount == 0;
	}

}
