package clide.model;

/**
 * One thing the compiler said about one line of one file, as collected by the
 * last build - see DiagnosticsReport for the set of them and the counts.
 */
public record Diagnostic(String path, int line, Severity severity, String message) {

	/** LSP DiagnosticSeverity, named rather than left as the wire's 1/2/3/4. */
	public enum Severity {
		ERROR, WARNING, INFO;

		/** LSP codes: 1 error, 2 warning, anything else treated as informational. */
		public static Severity ofLspCode(final long code) {
			if (code == 1)
				return ERROR;

			if (code == 2)
				return WARNING;

			return INFO;
		}

		public String label() {
			return name().toLowerCase();
		}
	}

	public Diagnostic {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");

		if (severity == null)
			throw new IllegalArgumentException("severity must not be null");

		if (message == null)
			throw new IllegalArgumentException("message must not be null");
	}

	/** "[error] line 42: cannot find symbol" - the per-file entry, path excluded. */
	public String display() {
		return "[" + severity.label() + "] line " + line + ": " + message;
	}

}
