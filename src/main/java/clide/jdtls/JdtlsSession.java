package clide.jdtls;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drives a full jdtls session end-to-end: LSP handshake (with Gradle/Maven
 * import disabled so an Eclipse .classpath is used instead - see JDTLS.md), a
 * whole-project build, and collecting/reporting compile diagnostics. This is
 * what turns "start the jdtls process" into "actually get compiler feedback
 * through jdtls".
 *
 * Uses java/buildWorkspace rather than opening every source file one by one:
 * opening files individually (textDocument/didOpen) is fine for a handful of
 * files but does not scale - on a project the size of PlantUML (thousands of
 * files) it took minutes, whereas java/buildWorkspace alone reports the same
 * diagnostics for the whole project in well under a second (see JDTLS.md).
 */
public class JdtlsSession {

	private static final List<String> CONVENTIONAL_SOURCE_FOLDERS = List.of("src/main/java", "src/main/resources",
			"src/test/java", "src/test/resources");

	/**
	 * Per-project jar dependency cache - see JDTLS.md. Populated by hand (or by a
	 * future clide command); clide only reads it.
	 */
	private static final String JARS_DIR = ".clide";

	private final JdtlsLauncher launcher;
	private final Path projectRoot;
	private LspClient client;
	private Thread notificationThread;
	private volatile boolean ready;
	private final Map<String, List<Map<String, Object>>> diagnosticsByUri = new ConcurrentHashMap<>();

	public JdtlsSession(final JdtlsLauncher launcher, final Path projectRoot) {
		this.launcher = launcher;
		this.projectRoot = projectRoot;
	}

	public boolean isReady() {
		return ready;
	}

	/** Starts jdtls if needed and performs the initialize/initialized handshake. */
	public void start() throws IOException, InterruptedException, LspClient.TimeoutException {
		if (ready)
			return;

		if (launcher.isRunning() == false)
			launcher.start();

		ensureDotFilesPresent();

		client = new LspClient(launcher.process().getOutputStream(), launcher.process().getInputStream());
		notificationThread = new Thread(this::processNotifications, "jdtls-notifications");
		notificationThread.setDaemon(true);
		notificationThread.start();

		final Map<String, Object> response = client.request("initialize", initializeParams(), 120);
		if (response.containsKey("error"))
			throw new IOException("jdtls initialize failed: " + response.get("error"));

		client.notify("initialized", new LinkedHashMap<>());
		ready = true;

		waitForServiceReady(60 * 4);
	}

	/**
	 * Checks whether .project and .classpath are present at the project root. Each
	 * one is created independently (with defaults "that work") if missing - this is
	 * the case when clide opens a checkout that was never set up as an Eclipse
	 * project (e.g. a fresh PlantUML clone without ./gradlew eclipse run yet).
	 * Source folders are guessed heuristically by checking which of the
	 * conventional Maven/Gradle layout directories actually exist on disk.
	 *
	 * Deliberately does NOT add a Gradle classpath container: without a real Gradle
	 * import (disabled - see initializeParams()), such a container never resolves
	 * anyway, so external dependencies stay unresolved either way - this at least
	 * gets the project recognized and its own source compiled.
	 */
	private void ensureDotFilesPresent() throws IOException {
		final Path projectFile = projectRoot.resolve(".project");
		final Path classpathFile = projectRoot.resolve(".classpath");

		if (Files.exists(projectFile) == false)
			Files.writeString(projectFile, buildDotProject(), StandardCharsets.UTF_8);

		if (Files.exists(classpathFile) == false)
			Files.writeString(classpathFile, buildDotClasspath(detectSourceFolders()), StandardCharsets.UTF_8);
	}

	private List<String> detectSourceFolders() {
		final List<String> found = new ArrayList<>();
		for (final String candidate : CONVENTIONAL_SOURCE_FOLDERS)
			if (Files.isDirectory(projectRoot.resolve(candidate)))
				found.add(candidate);

		if (found.isEmpty() && Files.isDirectory(projectRoot.resolve("src")))
			found.add("src");

		return found;
	}

	private String buildDotProject() {
		final String name = projectRoot.getFileName().toString();
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>%s</name>
					<comment></comment>
					<projects>
					</projects>
					<buildSpec>
						<buildCommand>
							<name>org.eclipse.jdt.core.javabuilder</name>
							<arguments>
							</arguments>
						</buildCommand>
					</buildSpec>
					<natures>
						<nature>org.eclipse.jdt.core.javanature</nature>
					</natures>
				</projectDescription>
				""".formatted(name);
	}

	private String buildDotClasspath(final List<String> sourceFolders) {
		final StringBuilder xml = new StringBuilder();
		xml.append("""
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
				""");
		for (final String folder : sourceFolders)
			xml.append("\t<classpathentry kind=\"src\" path=\"%s\"/>\n".formatted(folder));

		for (final String jar : detectJarLibs())
			xml.append("\t<classpathentry kind=\"lib\" path=\"%s\"/>\n".formatted(jar));

		xml.append("""
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>
				""");
		return xml.toString();
	}

	/**
	 * Jars found in <project>/.clide (flat, non-recursive) - a per-project cache
	 * populated ahead of time (e.g. with the JUnit/AssertJ/etc. jars a project's
	 * tests need), since clide's sandbox cannot reach Maven Central to resolve them
	 * itself. Only used here, when clide generates .classpath from scratch (see
	 * ensureDotFilesPresent doc) - a pre-existing, hand-maintained .classpath is
	 * never modified.
	 */
	private List<String> detectJarLibs() {
		final Path jarsDir = projectRoot.resolve(JARS_DIR);
		if (Files.isDirectory(jarsDir) == false)
			return List.of();

		final List<String> jars = new ArrayList<>();
		try (Stream<Path> entries = Files.list(jarsDir)) {
			entries.filter(p -> p.toString().endsWith(".jar")).sorted()
					.forEach(p -> jars.add(p.toAbsolutePath().toString().replace('\\', '/')));
		} catch (final IOException e) {
			// .clide present but unreadable - classpath just ends up without these jars
		}
		return jars;
	}

	/** Triggers a full project build via jdtls and waits for the result. */
	public void build() throws IOException, InterruptedException, LspClient.TimeoutException {
		diagnosticsByUri.clear();
		final Map<String, Object> response = client.request("java/buildWorkspace", Boolean.TRUE, 300);
		if (response.containsKey("error"))
			throw new IOException("java/buildWorkspace failed: " + response.get("error"));

		// Diagnostics for files with problems arrive as notifications around
		// the same time as the response - give them a moment to land.
		Thread.sleep(2000);
	}

	/**
	 * Resolves symbolText as a whole word on the given (1-based) line of file, then
	 * sends lspMethod ("textDocument/definition" or "textDocument/typeDefinition")
	 * at that position against this session. Shared by GotoDefinitionCommand and
	 * GotoTypeDefinitionCommand - only the LSP method name differs between the two.
	 *
	 * No textDocument/didOpen is sent first: this relies on jdtls already having
	 * the file in its compiled model from the last build() (see JDTLS.md, section
	 * 0bis) rather than on editor-style open/close tracking. To be revisited if
	 * that turns out not to be enough in practice.
	 *
	 * Returns one formatted "path:line: line content" entry per location in the
	 * response, in server order; an empty list if the response was empty/null.
	 */
	public List<String> goToPosition(final String lspMethod, final Path file, final int oneBasedLine,
			final String symbolText) throws IOException, InterruptedException, LspClient.TimeoutException {
		if (Files.isRegularFile(file) == false)
			throw new IOException("Not a file: " + file);

		final List<String> fileLines = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (oneBasedLine < 1 || oneBasedLine > fileLines.size())
			throw new IOException(
					"Line " + oneBasedLine + " out of range (file has " + fileLines.size() + " line(s)): " + file);

		final int column = findWholeWordColumn(fileLines.get(oneBasedLine - 1), symbolText);
		if (column < 0)
			throw new IOException("Symbol '" + symbolText + "' not found on line " + oneBasedLine + " of " + file);

		final Map<String, Object> textDocument = new LinkedHashMap<>();
		textDocument.put("uri", file.toUri().toString());
		final Map<String, Object> position = new LinkedHashMap<>();
		position.put("line", oneBasedLine - 1);
		position.put("character", column);
		final Map<String, Object> params = new LinkedHashMap<>();
		params.put("textDocument", textDocument);
		params.put("position", position);

		final Map<String, Object> response = client.request(lspMethod, params, 30);
		if (response.containsKey("error"))
			throw new IOException(lspMethod + " failed: " + response.get("error"));

		return formatLocations(response.get("result"));
	}

	/** Column (0-based) of the first whole-word match of symbol on line, or -1. */
	private int findWholeWordColumn(final String line, final String symbol) {
		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b").matcher(line);
		return matcher.find() ? matcher.start() : -1;
	}

	/**
	 * Accepts either a single Location, a Location[], or null/absent - the LSP
	 * response shapes allowed for definition/typeDefinition.
	 */
	@SuppressWarnings("unchecked")
	private List<String> formatLocations(final Object result) {
		final List<Object> rawLocations;
		if (result instanceof List)
			rawLocations = (List<Object>) result;
		else if (result instanceof Map)
			rawLocations = List.of(result);
		else
			rawLocations = List.of();

		final List<String> formatted = new ArrayList<>();
		for (final Object item : rawLocations)
			if (item instanceof Map)
				formatted.add(formatLocation((Map<String, Object>) item));

		return formatted;
	}

	/**
	 * Also understands LocationLink (targetUri/targetSelectionRange) in case a
	 * future capabilities change makes jdtls prefer that shape over plain Location
	 * (uri/range) - harmless either way since only one shape is ever populated.
	 */
	@SuppressWarnings("unchecked")
	private String formatLocation(final Map<String, Object> location) {
		final String uri = location.get("uri") != null ? (String) location.get("uri")
				: (String) location.get("targetUri");
		final Map<String, Object> range = location.get("range") != null ? (Map<String, Object>) location.get("range")
				: (Map<String, Object>) location.get("targetSelectionRange");

		long line = -1;
		if (range != null) {
			final Map<String, Object> start = (Map<String, Object>) range.get("start");
			if (start != null && start.get("line") instanceof Number)
				line = ((Number) start.get("line")).longValue() + 1;

		}

		final String locationLabel = shortName(uri) + ":" + line;
		final String lineText = readLineSafely(uri, line);
		return lineText == null ? locationLabel : locationLabel + ": " + lineText;
	}

	/** Best-effort: null on any failure (unreadable file, malformed URI, ...). */
	private String readLineSafely(final String uri, final long oneBasedLine) {
		if (uri == null || oneBasedLine < 1)
			return null;

		try {
			final Path path = Paths.get(new URI(uri));
			final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			if (oneBasedLine > lines.size())
				return null;

			return lines.get((int) oneBasedLine - 1).strip();
		} catch (final Exception e) {
			return null;
		}
	}

	/**
	 * Prints a summary of the diagnostics collected by the last build(). If
	 * printOnlyError is true, only error-level diagnostics are listed in detail
	 * (warnings/info are still counted in the summary line, just not printed one by
	 * one).
	 */
	public void reportDiagnostics(final PrintStream out, boolean printOnlyError) {
		if (diagnosticsByUri.isEmpty()) {
			out.println("jdtls: no diagnostics (project not recognized, or nothing to report)");
			return;
		}

		final Map<String, List<Map<String, Object>>> sorted = new TreeMap<>(diagnosticsByUri);
		int errorCount = 0;
		int warningCount = 0;
		int filesWithIssues = 0;
		for (final Map.Entry<String, List<Map<String, Object>>> entry : sorted.entrySet()) {
			final List<Map<String, Object>> diagnostics = entry.getValue();
			if (diagnostics.isEmpty())
				continue;

			filesWithIssues++;
			boolean headerPrinted = false;
			for (final Map<String, Object> diagnostic : diagnostics) {
				final Object severity = diagnostic.get("severity");
				final long severityCode = severity instanceof Number ? ((Number) severity).longValue() : 0;
				if (severityCode == 1)
					errorCount++;
				else if (severityCode == 2)
					warningCount++;

				if (printOnlyError && severityCode != 1)
					continue;

				if (headerPrinted == false) {
					out.println(shortName(entry.getKey()) + ":");
					headerPrinted = true;
				}
				out.println("  " + formatDiagnostic(diagnostic));
			}
		}

		if (errorCount == 0 && warningCount == 0)
			out.println("jdtls: " + sorted.size() + " file(s) with tracked diagnostics, no errors or warnings");
		else
			out.println("jdtls: " + errorCount + " error(s), " + warningCount + " warning(s) in " + filesWithIssues
					+ " file(s)");

	}

	@SuppressWarnings("unchecked")
	private String formatDiagnostic(final Map<String, Object> diagnostic) {
		final Object severity = diagnostic.get("severity");
		final long severityCode = severity instanceof Number ? ((Number) severity).longValue() : 0;
		final String severityLabel = severityCode == 1 ? "error" : severityCode == 2 ? "warning" : "info";
		final Map<String, Object> range = (Map<String, Object>) diagnostic.get("range");
		long line = -1;
		if (range != null) {
			final Map<String, Object> start = (Map<String, Object>) range.get("start");
			if (start != null && start.get("line") instanceof Number)
				line = ((Number) start.get("line")).longValue() + 1;

		}
		return "[" + severityLabel + "] line " + line + ": " + diagnostic.get("message");
	}

	private String shortName(final String uri) {
		final String prefix = projectUri();
		if (uri.equals(prefix))
			return "(project)";
		if (uri.startsWith(prefix))
			return uri.substring(prefix.length() + 1);

		return uri;
	}

	/**
	 * Attempts a graceful LSP shutdown, then stops the underlying process either
	 * way.
	 */
	public void stop() {
		if (client != null && ready) {
			try {
				client.request("shutdown", null, 5);
				client.notify("exit", new LinkedHashMap<>());
			} catch (final Exception e) {
				// best effort - fall through to hard stop below
			}
			client.close();
		}
		ready = false;
		launcher.stop();
	}

	@SuppressWarnings("unchecked")
	private void processNotifications() {
		try {
			while (true) {
				final Map<String, Object> notification = client.notifications().take();
				final Object method = notification.get("method");
				if ("textDocument/publishDiagnostics".equals(method)) {
					final Map<String, Object> params = (Map<String, Object>) notification.get("params");
					if (params != null) {
						final String uri = (String) params.get("uri");
						final List<Object> rawDiagnostics = (List<Object>) params.getOrDefault("diagnostics",
								List.of());
						final List<Map<String, Object>> diagnostics = new ArrayList<>();
						for (final Object item : rawDiagnostics)
							diagnostics.add((Map<String, Object>) item);

						diagnosticsByUri.put(uri, diagnostics);
					}
				}
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void waitForServiceReady(final long timeoutSeconds) throws InterruptedException {
		// Best-effort: jdtls reports "Started"/"ServiceReady" via language/status once
		// indexing is done, but on a project this small it's not essential to wait for
		// -
		// a short fixed delay is simpler and was sufficient during validation
		// (JDTLS.md).
		Thread.sleep(TimeUnit.SECONDS.toMillis(Math.min(timeoutSeconds, 15)));
	}

	private Map<String, Object> initializeParams() {
		final String rootUri = projectUri();

		final Map<String, Object> workspaceFolder = new LinkedHashMap<>();
		workspaceFolder.put("uri", rootUri);
		workspaceFolder.put("name", projectRoot.getFileName().toString());

		final Map<String, Object> gradleSettings = new LinkedHashMap<>();
		gradleSettings.put("enabled", false);
		final Map<String, Object> mavenSettings = new LinkedHashMap<>();
		mavenSettings.put("enabled", false);
		final Map<String, Object> importSettings = new LinkedHashMap<>();
		importSettings.put("gradle", gradleSettings);
		importSettings.put("maven", mavenSettings);
		final Map<String, Object> javaSettings = new LinkedHashMap<>();
		javaSettings.put("import", importSettings);
		final Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("java", javaSettings);
		final Map<String, Object> initializationOptions = new LinkedHashMap<>();
		initializationOptions.put("settings", settings);

		final Map<String, Object> publishDiagnostics = new LinkedHashMap<>();
		publishDiagnostics.put("relatedInformation", true);
		final Map<String, Object> textDocumentCapabilities = new LinkedHashMap<>();
		textDocumentCapabilities.put("publishDiagnostics", publishDiagnostics);
		final Map<String, Object> capabilities = new LinkedHashMap<>();
		capabilities.put("textDocument", textDocumentCapabilities);

		final Map<String, Object> params = new LinkedHashMap<>();
		params.put("processId", null);
		params.put("rootUri", rootUri);
		params.put("workspaceFolders", List.of(workspaceFolder));
		params.put("capabilities", capabilities);
		params.put("initializationOptions", initializationOptions);
		return params;
	}

	/**
	 * file:// URI for the project root, built via Path.toUri() (not string
	 * concatenation) so it works on Windows too - "file://" + path produces an
	 * invalid URI on Windows (backslashes, drive letter parsed as authority).
	 * Path.toUri() adds a trailing slash for directories; stripped here so the
	 * result matches what jdtls expects and what shortName() strips against.
	 */
	private String projectUri() {
		final String uri = projectRoot.toAbsolutePath().toUri().toString();
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

}
