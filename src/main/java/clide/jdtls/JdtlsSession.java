package clide.jdtls;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import clide.core.FilesRepository;
import clide.core.Position;
import clide.json.Monomorphic;

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

	private final JdtlsLauncher launcher;
	private final FilesRepository filesRepository;
	private final EclipseDescriptorBuilder descriptor;
	private LspClient client;
	private Thread notificationThread;
	private volatile boolean ready;
	private EclipseProjectFiles eclipseFiles;
	private final Map<String, List<Monomorphic>> diagnosticsByUri = new ConcurrentHashMap<>();

	/**
	 * Absolute path -&gt; mtime, as of the end of the last build() - see
	 * refreshChangedFiles().
	 */
	private final Map<String, Long> sourceFileTimestamps = new ConcurrentHashMap<>();

	public JdtlsSession(final JdtlsLauncher launcher, final FilesRepository filesRepository) {
		this.launcher = launcher;
		this.filesRepository = filesRepository;
		this.descriptor = EclipseDescriptorBuilder.forProject(filesRepository.getProjectRoot());
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

		// Before descriptor.buildDotClasspath() below reads .clide/tmp/jar-junit/ -
		// see JunitVendorJars - so a project with no JUnit of its own still gets one
		// it can compile its tests against.
		JunitVendorJars.ensurePresent(filesRepository.getProjectRoot());

		eclipseFiles = EclipseProjectFiles.forProject(filesRepository.getProjectRoot());
		eclipseFiles.stage(descriptor.buildDotProject(), descriptor.buildDotClasspath());

		client = new LspClient(launcher.process().getOutputStream(), launcher.process().getInputStream());
		notificationThread = new Thread(this::processNotifications, "jdtls-notifications");
		notificationThread.setDaemon(true);
		notificationThread.start();

		final Monomorphic response = client.request("initialize", initializeParams(), 120);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("jdtls initialize failed: " + error);

		client.notify("initialized", Monomorphic.mapBuilder().build());
		ready = true;

		waitForServiceReady(60 * 4);
	}

	/**
	 * Puts .project/.classpath back the way stage() (called from start(), above)
	 * found them - or removes clide's own if there was nothing to restore - now
	 * that jdtls has actually finished importing the project, not merely
	 * completed the LSP handshake. Must be called only once build() (the initial
	 * one, right after start()) has returned - see EclipseProjectFiles' class
	 * doc for why restoring any earlier would risk a race against jdtls still
	 * reading the files it was just handed. A no-op if start() never staged
	 * anything (e.g. this session was never actually started).
	 */
	public void restoreEclipseFiles() throws IOException {
		if (eclipseFiles != null)
			eclipseFiles.unstage();
	}

	/** Triggers a full project build via jdtls and waits for the result. */
	public void build() throws IOException, InterruptedException, LspClient.TimeoutException {
		// Snapshotted before the build, not after: a file edited while the build
		// is running would otherwise be recorded with its new timestamp and
		// counted as already built, and the next rebuild would skip it.
		snapshotSourceFiles();
		diagnosticsByUri.clear();
		final Monomorphic response = client.request("java/buildWorkspace", Monomorphic.createBoolean(true), 300);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("java/buildWorkspace failed: " + error);

		// Diagnostics for files with problems arrive as notifications around
		// the same time as the response - give them a moment to land.
		Thread.sleep(2000);
	}

	/**
	 * Tells jdtls which .java files changed on disk since the last build, so the
	 * build that follows compiles what is actually there now. Returns how many
	 * files were reported.
	 *
	 * Needed because jdtls' model is not a view of the filesystem: it is an Eclipse
	 * workspace, which only learns of a change made outside its own editing session
	 * when someone tells it. clide never opens files (textDocument/didOpen) - it
	 * builds the whole project instead, on purpose, because opening PlantUML's 3600
	 * files one by one takes minutes (see JDTLS.md), so nothing else here would
	 * ever tell jdtls a file moved on.
	 *
	 * Measured on PlantUML, what a forced java/buildWorkspace does and does not
	 * catch on its own, without this notification:
	 *
	 * - an edit to a file that already existed at the last build: caught. The
	 * forced build re-reads it. - a newly created .java file: NOT caught. A new
	 * file that doesn't compile at all was reported as "0 errors" - the worst
	 * possible answer, since it reads exactly like success.
	 *
	 * So this exists for the second case (and symmetrically for deletions, whose
	 * diagnostics would otherwise linger after the file is gone). Sending events
	 * for edits too costs nothing and keeps one code path.
	 *
	 * The comparison is a plain path/mtime snapshot taken at the end of every
	 * build(), diffed against the tree as it stands now: a file whose timestamp
	 * moved is Changed(2), one absent from the snapshot is Created(1), one gone
	 * from disk is Deleted(3).
	 */
	public int refreshChangedFiles() throws IOException {
		final Map<String, Long> current = filesRepository.currentSourceFiles();
		final List<Monomorphic> events = new ArrayList<>();

		for (final Map.Entry<String, Long> entry : current.entrySet()) {
			final Long previous = sourceFileTimestamps.get(entry.getKey());
			if (previous == null)
				events.add(fileEvent(entry.getKey(), 1));
			else if (previous.equals(entry.getValue()) == false)
				events.add(fileEvent(entry.getKey(), 2));
		}
		for (final String path : sourceFileTimestamps.keySet())
			if (current.containsKey(path) == false)
				events.add(fileEvent(path, 3));

		if (events.isEmpty())
			return 0;

		client.notify("workspace/didChangeWatchedFiles", Monomorphic.mapBuilder().putList("changes", events).build());

		// One-way notification: jdtls refreshes the affected resources when it
		// gets to it, and says nothing when it's done. Without this pause the
		// build below can start against the model as it was.
		try {
			Thread.sleep(1000);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return events.size();
	}

	private Monomorphic fileEvent(final String path, final int type) {
		return Monomorphic.mapBuilder() //
				.putString("uri", Paths.get(path).toUri().toString()) //
				.putNumber("type", type) //
				.build();
	}

	private void snapshotSourceFiles() {
		try {
			sourceFileTimestamps.clear();
			sourceFileTimestamps.putAll(filesRepository.currentSourceFiles());
		} catch (final IOException e) {
			// Best effort: a failure here just means the next
			// refreshChangedFiles() reports more files than strictly changed.
		}
	}


	/**
	 * Sends lspMethod ("textDocument/definition", "textDocument/typeDefinition", or
	 * "textDocument/implementation") at position against this session.
	 * Shared by GotoDefinitionCommand, GotoTypeDefinitionCommand and
	 * GotoImplementationCommand - only the LSP method name differs between them.
	 * See the other overload for requests that also need an LSP request-level
	 * "context" object (currently only textDocument/references does).
	 *
	 * position is already known to name a real file/line/word - it can only have
	 * come from Position.parse(), which validated all of that up front (see
	 * ParamType.POSITION, ClideDaemon.validate()) - so no re-validation happens
	 * here.
	 *
	 * No textDocument/didOpen is sent first: this relies on jdtls already having
	 * the file in its compiled model from the last build() (see JDTLS.md, section
	 * 0bis) rather than on editor-style open/close tracking. To be revisited if
	 * that turns out not to be enough in practice.
	 *
	 * Returns one formatted "path:line: line content" entry per location in the
	 * response, in server order; an empty list if the response was empty/null.
	 */
	public List<String> goToPosition(final String lspMethod, final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return goToPosition(lspMethod, position, null);
	}

	/**
	 * Same as the other overload, with an extra LSP request-level "context" object
	 * merged into the request params when non-null. Added for
	 * GotoReferencesCommand: textDocument/references is the one goto_* request that
	 * needs one ({"includeDeclaration": false} - only real usages matter, not the
	 * declaration itself, which is this command's own input already); the other
	 * three goto_* commands keep going through the 2-arg overload above, which
	 * passes null here.
	 */
	public List<String> goToPosition(final String lspMethod, final Position position, final Monomorphic context)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request(lspMethod,
				JdtlsResponses.positionParams(position.file(), position.line(), position.column(), context), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException(lspMethod + " failed: " + error);

		return formatLocations(response.getOrNull("result"));
	}

	/**
	 * textDocument/hover: the signature/Javadoc jdtls knows for the symbol at
	 * position itself - as opposed to goToPosition(), which locates some *other*
	 * place (a definition, an implementation), hover explains this exact symbol
	 * where it stands. Returns jdtls' hover text verbatim (already Markdown,
	 * printed as-is - not reformatted), or "<no hover info>" if jdtls had
	 * nothing to say (e.g. the symbol's type can't be resolved - no matching jar
	 * in .clide - or hover just doesn't apply to this kind of symbol).
	 */
	public String hover(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/hover",
				JdtlsResponses.positionParams(position.file(), position.line(), position.column()), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/hover failed: " + error);

		return formatHover(response.getOrNull("result"));
	}

	/**
	 * textDocument/documentSymbol: lists the direct members (methods, fields,
	 * constructors - not further-nested inner types' own members) of the
	 * class/interface/enum named position.name(), declared at position.line() of
	 * position.file() - here position picks which type to inspect rather than
	 * where to jump/what to explain. Requires hierarchicalDocumentSymbolSupport
	 * (see initializeParams()) - without declaring it, jdtls falls back to a flat
	 * SymbolInformation[] with no "children" at all, and this could never find any
	 * member.
	 *
	 * Returns one "[kind] path:line: line content" entry per member, in
	 * documentSymbol's own order.
	 */
	public List<String> listMembers(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = position.file().toUri().toString();
		final Monomorphic typeNode = findTypeNode(JdtlsResponses.documentSymbols(client, uri), position.name(),
				position.line() - 1);
		if (typeNode.isMap() == false)
			throw new IOException("No class/interface/enum named '" + position.name() + "' declared at line "
					+ position.line() + " of " + position.file()
					+ " (list_members only inspects types, not methods/fields)");

		return formatMembers(uri, JdtlsResponses.childrenOf(typeNode));
	}

	/**
	 * textDocument/implementation on a *method*, plus a second pass
	 * (MethodOverrideRecovery) that recovers the overrides jdtls silently omits
	 * - see that class' doc for why jdtls' own SearchPattern misses generic
	 * overrides, and what the recovery pass does about it.
	 *
	 * Returns one formatted "path:line: line content" entry per location, in
	 * jdtls' own order first, recovered ones appended after - formatting
	 * (project-relative path shortening, reading the line's own text) is this
	 * class' job; MethodOverrideRecovery only ever deals in raw Monomorphic
	 * locations.
	 */
	public List<String> findMethodImplementations(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final List<Monomorphic> merged = new MethodOverrideRecovery(client).find(position);

		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic location : merged)
			formatted.add(formatLocation(location));

		return formatted;
	}

	/**
	 * workspace/symbol: finds symbols by name anywhere in the project - the lookup
	 * goto_* itself needs a file+line to already know. Matching (fuzzy, camelCase,
	 * exact - whatever jdtls itself implements) is entirely up to the server; clide
	 * sends query as-is and applies no filtering of its own on the results.
	 *
	 * Returns one "[kind] path:line: line content" entry per symbol in the
	 * response, in server order - see formatSymbol(); an empty list if the response
	 * was empty/null.
	 */
	public List<String> findSymbol(final String query)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder().putString("query", query).build();

		final Monomorphic response = client.request("workspace/symbol", params, 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("workspace/symbol failed: " + error);

		return formatSymbols(response.getOrNull("result"));
	}

	/** Accepts either a SymbolInformation[], or null/absent. */
	private List<String> formatSymbols(final Monomorphic result) {
		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic item : result.elementsOf())
			if (item.isMap())
				formatted.add(formatSymbol(item));

		return formatted;
	}

	/**
	 * "[kind] path:line: line content" - the location part reuses formatLocation()
	 * as-is: a SymbolInformation's own "location" field is a plain Location
	 * (uri+range), the same shape formatLocation() already renders for
	 * find_declaration/find_implementation.
	 */
	private String formatSymbol(final Monomorphic symbol) {
		final Monomorphic location = symbol.getOrNull("location");
		final String locationText = location.isMap() == false
				? String.valueOf(symbol.getOrNull("name").stringOrNull()) + ": <no location>"
				: formatLocation(location);

		return "[" + symbolKindLabel(symbol.getOrNull("kind")) + "] " + locationText;
	}

	/**
	 * Human label for an LSP SymbolKind code - only the kinds a Java source file
	 * can actually produce are named individually, everything else (there shouldn't
	 * be any, in practice) falls back to "symbol" rather than a bare number.
	 */
	private String symbolKindLabel(final Monomorphic kind) {
		return switch ((int) kind.longOrDefault(0)) {
		case 4 -> "package";
		case 5 -> "class";
		case 6 -> "method";
		case 7 -> "property";
		case 8 -> "field";
		case 9 -> "constructor";
		case 10 -> "enum";
		case 11 -> "interface";
		case 12 -> "function";
		case 13 -> "variable";
		case 14 -> "constant";
		case 22 -> "enum member";
		case 23 -> "struct";
		default -> "symbol";
		};
	}

	/**
	 * Recursively searches a documentSymbol tree (nodes, and each node's own
	 * "children") for a type-kind node (see JdtlsResponses.isTypeKind()) named
	 * name and declared at zeroBasedLine (its own selectionRange, i.e. just the
	 * name token -
	 * matches position.line()-1, already whole-word-validated by Position.parse()).
	 * Returns the first match found (depth-first), or null.
	 */
	private Monomorphic findTypeNode(final List<Monomorphic> nodes, final String name, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false)
				continue;

			if (isMatchingTypeNode(node, name, zeroBasedLine))
				return node;

			final Monomorphic foundInChildren = findTypeNode(JdtlsResponses.childrenOf(node), name, zeroBasedLine);
			if (foundInChildren.isMap())
				return foundInChildren;
		}
		return Monomorphic.createNull();
	}

	private boolean isMatchingTypeNode(final Monomorphic node, final String name, final int zeroBasedLine) {
		if (JdtlsResponses.isTypeKind(node) == false)
			return false;
		// jdtls names a generic type after its source spelling, type parameters
		// included ("AbstractUGraphic<O>"), while <position> only ever carries the
		// bare name - Position.parse() matched it as a whole word on the line. An
		// equals() on the raw name therefore never matched a generic type, and
		// list_members failed on every one of them.
		if (name.equals(withoutTypeParameters(node.getOrNull("name"))) == false)
			return false;

		return JdtlsResponses.lineOf(JdtlsResponses.startOf(node.getOrNull("selectionRange"))) == zeroBasedLine;
	}

	/**
	 * "AbstractUGraphic&lt;O&gt;" -&gt; "AbstractUGraphic"; null unless rawName is
	 * a String.
	 */
	private String withoutTypeParameters(final Monomorphic rawName) {
		final String name = rawName.stringOrNull();
		if (name == null)
			return null;

		final int angle = name.indexOf('<');
		return angle < 0 ? name : name.substring(0, angle);
	}

	private List<String> formatMembers(final String uri, final List<Monomorphic> children) {
		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic member : children)
			if (member.isMap())
				formatted.add(formatMember(uri, member));

		return formatted;
	}

	/**
	 * "[kind] path:line: line content" - built the same way formatSymbol() builds
	 * one for workspace/symbol, except a documentSymbol child has no "location" of
	 * its own (uri+range together): the uri is the containing file's (same for
	 * every child, passed in), only "selectionRange" is on the child itself. A
	 * synthetic {"uri":..., "range":...} map lets formatLocation() render it
	 * exactly the same way regardless.
	 */
	private String formatMember(final String uri, final Monomorphic member) {
		final Monomorphic location = Monomorphic.mapBuilder() //
				.putString("uri", uri) //
				.put("range", member.getOrNull("selectionRange")) //
				.build();

		return "[" + symbolKindLabel(member.getOrNull("kind")) + "] " + formatLocation(location);
	}

	/**
	 * Renders a Hover response's "contents", which can be a plain string, a
	 * MarkupContent ({"value": "..."}), a (deprecated) MarkedString in the same
	 * {"value": "..."} shape, or an array mixing any of those - jdtls' own choice,
	 * not something clide controls, so every shape is handled rather than assumed.
	 */
	private String formatHover(final Monomorphic result) {
		if (result.isMap() == false)
			return "<no hover info>";

		final String text = hoverText(result.getOrNull("contents"));
		return text == null || text.isBlank() ? "<no hover info>" : text.strip();
	}

	private String hoverText(final Monomorphic contents) {
		if (contents.isString())
			return contents.asString();

		if (contents.isMap()) {
			final Monomorphic value = contents.getOrNull("value");
			if (value.isNull())
				return null;

			// toString() only for the shapes that are not a string already - on a
			// STRING it would add the quotes back and put them in the hover text.
			return value.isString() ? value.asString() : value.toString();
		}

		if (contents.isList()) {
			final StringBuilder combined = new StringBuilder();
			for (final Monomorphic item : contents.asList()) {
				final String itemText = hoverText(item);
				if (itemText != null) {
					if (combined.length() > 0)
						combined.append("\n\n");
					combined.append(itemText);
				}
			}
			return combined.length() == 0 ? null : combined.toString();
		}

		return null;
	}

	/**
	 * Accepts either a single Location, a Location[], or null/absent - the LSP
	 * response shapes allowed for definition/typeDefinition.
	 */
	private List<String> formatLocations(final Monomorphic result) {
		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic location : JdtlsResponses.rawLocations(result))
			formatted.add(formatLocation(location));

		return formatted;
	}

	/**
	 * Also understands LocationLink (targetUri/targetSelectionRange) in case a
	 * future capabilities change makes jdtls prefer that shape over plain Location
	 * (uri/range) - harmless either way since only one shape is ever populated.
	 */
	private String formatLocation(final Monomorphic location) {
		final String uri = JdtlsResponses.uriOf(location);
		final int zeroBasedLine = JdtlsResponses.lineOf(JdtlsResponses.startOf(JdtlsResponses.rangeOf(location)));
		final long line = zeroBasedLine == -1 ? -1 : zeroBasedLine + 1;

		final String locationLabel = shortName(uri) + ":" + line;
		final String lineText = JdtlsResponses.readLineSafely(uri, line);
		return lineText == null ? locationLabel : locationLabel + ": " + lineText;
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

		final Map<String, List<Monomorphic>> sorted = new TreeMap<>(diagnosticsByUri);
		int errorCount = 0;
		int warningCount = 0;
		int filesWithIssues = 0;
		for (final Map.Entry<String, List<Monomorphic>> entry : sorted.entrySet()) {
			final List<Monomorphic> diagnostics = entry.getValue();
			if (diagnostics.isEmpty())
				continue;

			filesWithIssues++;
			boolean headerPrinted = false;
			for (final Monomorphic diagnostic : diagnostics) {
				final long severityCode = diagnostic.getOrNull("severity").longOrDefault(0);
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

	private String formatDiagnostic(final Monomorphic diagnostic) {
		final long severityCode = diagnostic.getOrNull("severity").longOrDefault(0);
		final String severityLabel = severityCode == 1 ? "error" : severityCode == 2 ? "warning" : "info";
		final int zeroBasedLine = JdtlsResponses.lineOf(JdtlsResponses.startOf(diagnostic.getOrNull("range")));
		final long line = zeroBasedLine == -1 ? -1 : zeroBasedLine + 1;
		return "[" + severityLabel + "] line " + line + ": " + diagnostic.getOrNull("message").stringOrNull();
	}

	private String shortName(final String uri) {
		final String prefix = filesRepository.projectUri();
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
				client.request("shutdown", Monomorphic.createNull(), 5);
				client.notify("exit", Monomorphic.mapBuilder().build());
			} catch (final Exception e) {
				// best effort - fall through to hard stop below
			}
			client.close();
		}
		ready = false;
		launcher.stop();
	}

	private void processNotifications() {
		try {
			while (true) {
				final Monomorphic notification = client.notifications().take();
				if ("textDocument/publishDiagnostics".equals(notification.getOrNull("method").stringOrNull()) == false)
					continue;

				final Monomorphic params = notification.getOrNull("params");
				final String uri = params.getOrNull("uri").stringOrNull();
				if (uri == null)
					continue;

				diagnosticsByUri.put(uri, new ArrayList<>(params.getOrNull("diagnostics").elementsOf()));
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

	private Monomorphic initializeParams() {
		final String rootUri = filesRepository.projectUri();

		final Monomorphic workspaceFolder = Monomorphic.mapBuilder() //
				.putString("uri", rootUri) //
				.putString("name", filesRepository.getProjectRoot().getFileName().toString()) //
				.build();

		// The same immutable value for both - a Monomorphic cannot be modified
		// after the fact, so nothing can make one of the two drift.
		final Monomorphic disabled = Monomorphic.mapBuilder().putBoolean("enabled", false).build();
		final Monomorphic importSettings = Monomorphic.mapBuilder() //
				.put("gradle", disabled) //
				.put("maven", disabled) //
				.build();
		// Without this, workspace/symbol (and so find_symbol) only ever returns
		// types (classes/interfaces/enums/records/annotations), never methods -
		// confirmed empirically (see CLAUDE.md, "Capacites de jdtls"). Does NOT
		// cover fields: jdtls has no field search in workspace/symbol at all,
		// with or without this setting.
		final Monomorphic symbolsSettings = Monomorphic.mapBuilder() //
				.putBoolean("includeSourceMethodDeclarations", true) //
				.build();
		final Monomorphic javaSettings = Monomorphic.mapBuilder() //
				.put("import", importSettings) //
				.put("symbols", symbolsSettings) //
				.build();
		final Monomorphic initializationOptions = Monomorphic.mapBuilder()
				.put("settings", Monomorphic.mapBuilder().put("java", javaSettings).build()).build();

		final Monomorphic publishDiagnostics = Monomorphic.mapBuilder() //
				.putBoolean("relatedInformation", true) //
				.build();
		// Without this, jdtls has no signal that clide can handle the nested
		// DocumentSymbol[] shape (range/selectionRange/children) and falls back to a
		// flat SymbolInformation[] instead - which has no "children" at all, so
		// listMembers() could never find any member.
		final Monomorphic documentSymbolCapabilities = Monomorphic.mapBuilder() //
				.putBoolean("hierarchicalDocumentSymbolSupport", true) //
				.build();
		final Monomorphic textDocumentCapabilities = Monomorphic.mapBuilder() //
				.put("publishDiagnostics", publishDiagnostics) //
				.put("documentSymbol", documentSymbolCapabilities) //
				.build();
		final Monomorphic capabilities = Monomorphic.mapBuilder() //
				.put("textDocument", textDocumentCapabilities) //
				.build();

		return Monomorphic.mapBuilder() //
				.putNull("processId") //
				.putString("rootUri", rootUri) //
				.putList("workspaceFolders", List.of(workspaceFolder)) //
				.put("capabilities", capabilities) //
				.put("initializationOptions", initializationOptions) //
				.build();
	}

	/**
	 * file:// URI for the project root, built via Path.toUri() (not string
	 * concatenation) so it works on Windows too - "file://" + path produces an
	 * invalid URI on Windows (backslashes, drive letter parsed as authority).
	 * Path.toUri() adds a trailing slash for directories; stripped here so the
	 * result matches what jdtls expects and what shortName() strips against.
	 */
	// ------------------------------------------------------------------
	// workspace/executeCommand - jdtls' own commands, beyond plain LSP
	// ------------------------------------------------------------------

	/**
	 * Invokes one of the commands jdtls registers on top of the LSP protocol (see
	 * JDTDelegateCommandHandler in org.eclipse.jdt.ls.core). Returns the raw
	 * "result" - each caller below knows the shape it expects.
	 *
	 * A trap worth an hour of anyone's time: an argument that is a JSON *object*
	 * has to be sent as a JSON *string*. jdtls hands each argument to
	 * JSONUtility.toModel(), which understands a JsonElement, an instance of the
	 * target class, or a String it parses as JSON - and returns null for anything
	 * else. lsp4j has already turned the JSON object into a plain Map by then, so
	 * sending {"scope":"test"} yields a null options object and a
	 * NullPointerException wrapped as error -32001. Sending "{\"scope\":\"test\"}"
	 * works.
	 */
	private Monomorphic executeWorkspaceCommand(final String command, final List<Monomorphic> arguments,
			final long timeoutSeconds) throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder() //
				.putString("command", command) //
				.putList("arguments", arguments) //
				.build();

		final Monomorphic response = client.request("workspace/executeCommand", params, timeoutSeconds);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException(command + " failed: " + error);

		return response.getOrNull("result");
	}

	/**
	 * The classpath to run this project's tests on, as jdtls knows it: the output
	 * folders plus every jar of .clide/. Entries that do not exist on disk are
	 * dropped - jdtls reports an output folder nothing was ever written to as an
	 * Eclipse workspace path rather than a filesystem one.
	 *
	 * The "test" scope only differs from "runtime" when the test source folders
	 * are marked as such in .classpath - see EclipseDescriptorBuilder.buildDotClasspath().
	 */
	public List<String> testClasspath() throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic result = executeWorkspaceCommand("java.project.getClasspaths",
				List.of(Monomorphic.createString(filesRepository.projectUri()), Monomorphic.createString("{\"scope\":\"test\"}")), 60);
		if (result.isMap() == false)
			throw new IOException("java.project.getClasspaths returned no classpath: " + result);

		final List<String> entries = new ArrayList<>();
		for (final Monomorphic entry : result.getOrNull("classpaths").elementsOf()) {
			final String path = entry.stringOrNull();
			if (path != null && Files.exists(Paths.get(path)))
				entries.add(path);
		}

		return entries;
	}

	/**
	 * URIs of the java projects jdtls holds - one for a plain checkout, several
	 * for a multi-module repository.
	 */
	public List<String> projectUris() throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic result = executeWorkspaceCommand("java.project.getAll", List.of(), 30);
		final List<String> uris = new ArrayList<>();
		for (final Monomorphic uri : result.elementsOf())
			if (uri.isString())
				uris.add(uri.asString());

		return uris;
	}

	/**
	 * Turns a stack frame - "at demo.Calc.div(Calc.java:9)", exactly as a stack
	 * trace prints it - into the URI of the source file it points at, or null when
	 * jdtls cannot place it (a frame from a jar with no sources, typically). This
	 * is what lets a test failure be reported at a path the client can feed
	 * straight back into hover or find_reference.
	 */
	public String resolveStackTraceLocation(final String frame) {
		try {
			final Monomorphic result = executeWorkspaceCommand("java.project.resolveStackTraceLocation",
					List.of(Monomorphic.createString(frame), Monomorphic.createList()), 15);
			return result.stringOrNull();
		} catch (final Exception unresolvable) {
			return null;
		}
	}

}
