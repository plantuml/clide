package clide.jdtls;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import clide.command.answer.ErrorCode;
import clide.core.Delta;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.core.Monomorphic;
import clide.core.PositionException;
import clide.core.Snapshot;
import clide.model.CodeLocation;
import clide.model.Diagnostic;
import clide.model.DiagnosticsReport;
import clide.model.Listing;
import clide.model.Position;
import clide.model.SymbolHit;

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

	private Snapshot snapshot = Snapshot.empty();

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
	 * that jdtls has actually finished importing the project, not merely completed
	 * the LSP handshake. Must be called only once build() (the initial one, right
	 * after start()) has returned - see EclipseProjectFiles' class doc for why
	 * restoring any earlier would risk a race against jdtls still reading the files
	 * it was just handed. A no-op if start() never staged anything (e.g. this
	 * session was never actually started).
	 */
	public void restoreEclipseFiles() throws IOException {
		if (eclipseFiles != null)
			eclipseFiles.unstage();
	}

	/** Triggers a full project build via jdtls and waits for the result. */
	public void build() throws IOException, InterruptedException, LspClient.TimeoutException {
		// Snapshotted before the build, not after: a file edited while the build
		// is running would otherwise be recorded with its new content and
		// counted as already built, and the next rebuild would skip it.
		snapshot = Snapshot.build(filesRepository);
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
	 * Which files those are is Snapshot's own business: the snapshot taken by the
	 * last build(), compared with one taken of the tree as it stands now, yields
	 * the events to send - see Snapshot.fileEventsTo(). All that is left here is
	 * sending them and waiting for jdtls to catch up.
	 */
	public int refreshChangedFiles() throws IOException {
		final Delta delta = Snapshot.build(filesRepository).compareWithPreviousSnapshot(snapshot);

		if (delta.size() == 0)
			return 0;

		final List<Monomorphic> events = delta.fileEvents();

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

	/**
	 * Sends lspMethod ("textDocument/definition", "textDocument/typeDefinition", or
	 * "textDocument/implementation") at position against this session. Shared by
	 * GotoDefinitionCommand, GotoTypeDefinitionCommand and
	 * GotoImplementationCommand - only the LSP method name differs between them.
	 * See the other overload for requests that also need an LSP request-level
	 * "context" object (currently only textDocument/references does).
	 *
	 * position is already known to name a real file/line/word - it can only have
	 * come from PositionParser.parse(), which validated all of that up front (see
	 * ParamType.POSITION, ClideDaemon.validate()) - so no re-validation happens
	 * here.
	 *
	 * No textDocument/didOpen is sent first: this relies on jdtls already having
	 * the file in its compiled model from the last build() (see JDTLS.md, section
	 * 0bis) rather than on editor-style open/close tracking. To be revisited if
	 * that turns out not to be enough in practice.
	 *
	 * Returns one formatted "path:line:column:name line content" entry per location in
	 * the response, in server order; an empty list if the response was empty/null.
	 */
	public List<CodeLocation> goToPosition(final String lspMethod, final Position position)
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
	public List<CodeLocation> goToPosition(final String lspMethod, final Position position, final Monomorphic context)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request(lspMethod,
				JdtlsResponses.positionParams(fileOf(position), position.line(), position.column(), context), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException(lspMethod + " failed: " + error);

		return collectLocations(response.getOrNull("result"));
	}

	/**
	 * The file a &lt;position&gt; names, resolved against this session's project
	 * root - the one and only way this class turns a Position into a path.
	 * position.path() is project-relative (see Position), so resolving it with
	 * Paths.get() would silently aim at the daemon's own working directory
	 * instead; Position.fileIn() is where that reasoning lives.
	 */
	private Path fileOf(final Position position) {
		return position.fileIn(filesRepository.getProjectRoot());
	}

	/**
	 * textDocument/hover: the signature/Javadoc jdtls knows for the symbol at
	 * position itself - as opposed to goToPosition(), which locates some *other*
	 * place (a definition, an implementation), hover explains this exact symbol
	 * where it stands. Returns jdtls' hover text verbatim (already Markdown,
	 * printed as-is - not reformatted), or "<no hover info>" if jdtls had nothing
	 * to say (e.g. the symbol's type can't be resolved - no matching jar in .clide
	 * - or hover just doesn't apply to this kind of symbol).
	 */
	public String hover(final Position position) throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/hover",
				JdtlsResponses.positionParams(fileOf(position), position.line(), position.column()), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/hover failed: " + error);

		return formatHover(response.getOrNull("result"));
	}

	/**
	 * textDocument/documentSymbol: lists the direct members (methods, fields,
	 * constructors - not further-nested inner types' own members) of the
	 * class/interface/enum named position.name(), declared at position.line() of
	 * position.file() - here position picks which type to inspect rather than where
	 * to jump/what to explain. Requires hierarchicalDocumentSymbolSupport (see
	 * initializeParams()) - without declaring it, jdtls falls back to a flat
	 * SymbolInformation[] with no "children" at all, and this could never find any
	 * member.
	 *
	 * Returns one "[kind] path:line:column:name line content" entry per member, in
	 * documentSymbol's own order.
	 */
	public List<SymbolHit> listMembers(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = fileOf(position).toUri().toString();
		final Monomorphic typeNode = findTypeNode(JdtlsResponses.documentSymbols(client, uri), position.name(),
				position.line() - 1);
		if (typeNode.isMap() == false)
			throw new IOException(
					"No class/interface/enum named '" + position.name() + "' declared at line " + position.line()
							+ " of " + position.path() + " (list_members only inspects types, not methods/fields)");

		return collectMembers(uri, JdtlsResponses.childrenOf(typeNode));
	}

	/**
	 * textDocument/implementation on a *method*, plus a second pass
	 * (MethodOverrideRecovery) that recovers the overrides jdtls silently omits -
	 * see that class' doc for why jdtls' own SearchPattern misses generic
	 * overrides, and what the recovery pass does about it.
	 *
	 * Returns one formatted "path:line:column:name line content" entry per location, in jdtls'
	 * own order first, recovered ones appended after - formatting (project-relative
	 * path shortening, reading the line's own text) is this class' job;
	 * MethodOverrideRecovery only ever deals in raw Monomorphic locations.
	 */
	public List<CodeLocation> findMethodImplementations(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final List<Monomorphic> merged = new MethodOverrideRecovery(client, filesRepository.getProjectRoot())
				.find(position);

		final List<CodeLocation> located = new ArrayList<>();
		for (final Monomorphic location : merged) {
			final CodeLocation codeLocation = locationOf(location);
			if (codeLocation != null)
				located.add(codeLocation);
		}

		return located;
	}

	/**
	 * workspace/symbol: finds symbols by name anywhere in the project - the lookup
	 * goto_* itself needs a file+line to already know. Matching (fuzzy, camelCase,
	 * exact - whatever jdtls itself implements) is entirely up to the server; clide
	 * sends query as-is and applies no filtering of its own on the results.
	 *
	 * Returns one "[kind] path:line:column:name line content" entry per symbol in the
	 * response, in server order - see formatSymbol(); an empty list if the response
	 * was empty/null.
	 */
	public List<SymbolHit> findSymbol(final String query)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder().putString("query", query).build();

		final Monomorphic response = client.request("workspace/symbol", params, 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("workspace/symbol failed: " + error);

		return collectSymbols(response.getOrNull("result"));
	}

	/** Accepts either a SymbolInformation[], or null/absent. */
	private List<SymbolHit> collectSymbols(final Monomorphic result) {
		final List<SymbolHit> symbols = new ArrayList<>();
		for (final Monomorphic item : result.elementsOf())
			if (item.isMap())
				symbols.add(symbolOf(item));

		return symbols;
	}

	/**
	 * One workspace/symbol hit. The location part reuses locationOf() as-is: a
	 * SymbolInformation's own "location" field is a plain Location (uri+range),
	 * the same shape locationOf() already reads for
	 * find_declaration/find_implementation. A symbol jdtls returned without a
	 * location keeps a null one rather than being dropped - SymbolHit.display()
	 * falls back to its name, so it is still reported, just not navigable.
	 */
	private SymbolHit symbolOf(final Monomorphic symbol) {
		final Monomorphic location = symbol.getOrNull("location");
		final String name = String.valueOf(symbol.getOrNull("name").stringOrNull());
		return new SymbolHit(symbolKindLabel(symbol.getOrNull("kind")), name,
				location.isMap() ? locationOf(location) : null);
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
	 * "children") for a type-kind node (see JdtlsResponses.isTypeKind()) named name
	 * and declared at zeroBasedLine (its own selectionRange, i.e. just the name
	 * token - matches position.line()-1, already whole-word-validated by
	 * PositionParser.parse()). Returns the first match found (depth-first), or null.
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
		// bare name - PositionParser.parse() matched it as a whole word on the line. An
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

	private List<SymbolHit> collectMembers(final String uri, final List<Monomorphic> children) {
		final List<SymbolHit> members = new ArrayList<>();
		for (final Monomorphic member : children)
			if (member.isMap())
				members.add(memberOf(uri, member));

		return members;
	}

	/**
	 * One member of a type, built the same way symbolOf() builds one for
	 * workspace/symbol, except a documentSymbol child has no "location" of its own
	 * (uri+range together): the uri is the containing file's (same for every child,
	 * passed in), only "selectionRange" is on the child itself. A synthetic
	 * {"uri":..., "range":...} map lets locationOf() read it exactly the same way
	 * regardless.
	 */
	private SymbolHit memberOf(final String uri, final Monomorphic member) {
		final Monomorphic location = Monomorphic.mapBuilder() //
				.putString("uri", uri) //
				.put("range", member.getOrNull("selectionRange")) //
				.build();

		final String name = String.valueOf(member.getOrNull("name").stringOrNull());
		return new SymbolHit(symbolKindLabel(member.getOrNull("kind")), name, locationOf(location));
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
	private List<CodeLocation> collectLocations(final Monomorphic result) {
		final List<CodeLocation> locations = new ArrayList<>();
		for (final Monomorphic location : JdtlsResponses.rawLocations(result)) {
			final CodeLocation located = locationOf(location);
			if (located != null)
				locations.add(located);
		}

		return locations;
	}

	/**
	 * Also understands LocationLink (targetUri/targetSelectionRange) in case a
	 * future capabilities change makes jdtls prefer that shape over plain Location
	 * (uri/range) - harmless either way since only one shape is ever populated.
	 *
	 * Returns null for a location outside the project (a JDK/library source, a
	 * file in another module): clide's whole convention is to work on the open
	 * project's own files (see CLAUDE.md), and Position now enforces a
	 * project-relative path (see Position's own doc) - such a location has no
	 * project-relative path to give it. Filtered out silently, on purpose: a
	 * caller like find_implementation legitimately gets back fewer results, the
	 * same way an empty search elsewhere is not itself an error.
	 */
	private CodeLocation locationOf(final Monomorphic location) {
		final String uri = JdtlsResponses.uriOf(location);
		if (isInProject(uri) == false)
			return null;

		final Monomorphic start = JdtlsResponses.startOf(JdtlsResponses.rangeOf(location));
		final int line = JdtlsResponses.oneBased(JdtlsResponses.lineOf(start));
		final int column = JdtlsResponses.oneBased(JdtlsResponses.characterOf(start));

		// The raw line for the name (columns are counted against it, indentation
		// included), the stripped one for display - see JdtlsResponses.
		final String rawLine = JdtlsResponses.readRawLineSafely(uri, line);
		final String name = JdtlsResponses.identifierAt(rawLine, column);
		return new CodeLocation(new Position(md5Of(uri), shortName(uri), line, column, name),
				rawLine == null ? "" : rawLine.strip());
	}

	/**
	 * The signature of the file this location is in - what makes a printed
	 * position re-checkable when it comes back in (see Position and
	 * PositionParser.parse()).
	 *
	 * Raises rather than degrading, and that is the one place this producer
	 * differs from the rest of locationOf(): a missing name or an unreadable line
	 * yields an incomplete-but-honest token, whereas a location clide cannot sign
	 * would yield a *short* token - indistinguishable from one a client wrote
	 * deliberately without an md5, and therefore silently exempt from the check
	 * for the rest of its life. Better a failed command than a position that opts
	 * itself out.
	 *
	 * Read fresh every time, no cache: locationOf() already reads the whole file
	 * per location for its line text (JdtlsResponses.readRawLineSafely()), so
	 * hashing it costs less again than what is already being paid, and a cache
	 * held across a request is a cache that can hand back a signature the file no
	 * longer has - the exact failure this field exists to make impossible.
	 */
	private String md5Of(final String uri) {
		try {
			return Position.abbreviate(Md5Repository.md5Of(Paths.get(URI.create(uri))));
		} catch (final IOException | RuntimeException e) {
			throw new PositionException(ErrorCode.FILE_UNREADABLE,
					"Could not read " + shortName(uri) + " to sign the position it is in: " + e.getMessage());
		}
	}

	/** Whether uri names a file inside the project root (or the root itself). */
	private boolean isInProject(final String uri) {
		final String prefix = filesRepository.projectUri();
		return uri.equals(prefix) || uri.startsWith(prefix);
	}

	/**
	 * What the last build() found, as data - the counts over every diagnostic it
	 * collected, plus the (filtered, then capped) diagnostics themselves.
	 *
	 * Used to print its own summary to a PrintStream; it now returns a
	 * DiagnosticsReport and lets print_diagnostics/rebuild render it, so the same
	 * facts can be counted, capped and (one day) serialized instead of existing
	 * only as text.
	 *
	 * Two things the counts deliberately do NOT depend on. They are tallied over
	 * every diagnostic, before errorsOnly filters anything out - "3 error(s), 12
	 * warning(s)" is a statement about the project, and it would quietly become a
	 * statement about the excerpt if the filter came first. And they are tallied
	 * before maxResults caps anything, so Listing.truncated() compares against the
	 * real total rather than against the cap (see Listing).
	 *
	 * An empty diagnosticsByUri returns DiagnosticsReport.untracked(): jdtls holds
	 * nothing at all for this project, which means nothing was analyzed - a
	 * different statement from "analyzed and found clean", and not one to blur
	 * into it.
	 */
	public DiagnosticsReport diagnosticsReport(final boolean errorsOnly, final int maxResults) {
		if (diagnosticsByUri.isEmpty())
			return DiagnosticsReport.untracked();

		final Map<String, List<Monomorphic>> sorted = new TreeMap<>(diagnosticsByUri);
		final List<Diagnostic> kept = new ArrayList<>();
		int errorCount = 0;
		int warningCount = 0;
		int filesWithIssues = 0;
		for (final Map.Entry<String, List<Monomorphic>> entry : sorted.entrySet()) {
			final List<Monomorphic> diagnostics = entry.getValue();
			if (diagnostics.isEmpty())
				continue;

			filesWithIssues++;
			for (final Monomorphic diagnostic : diagnostics) {
				final Diagnostic.Severity severity = Diagnostic.Severity
						.ofLspCode(diagnostic.getOrNull("severity").longOrDefault(0));
				if (severity == Diagnostic.Severity.ERROR)
					errorCount++;
				else if (severity == Diagnostic.Severity.WARNING)
					warningCount++;

				if (errorsOnly && severity != Diagnostic.Severity.ERROR)
					continue;

				kept.add(diagnosticOf(shortName(entry.getKey()), severity, diagnostic));
			}
		}

		return new DiagnosticsReport(Listing.of(kept, maxResults), errorCount, warningCount, filesWithIssues,
				errorsOnly, true);
	}

	private Diagnostic diagnosticOf(final String path, final Diagnostic.Severity severity,
			final Monomorphic diagnostic) {
		final int zeroBasedLine = JdtlsResponses.lineOf(JdtlsResponses.startOf(diagnostic.getOrNull("range")));
		final int line = zeroBasedLine == -1 ? -1 : zeroBasedLine + 1;
		return new Diagnostic(path, line, severity, String.valueOf(diagnostic.getOrNull("message").stringOrNull()));
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
		// What clide can do with a WorkspaceEdit jdtls *answers with* - the return
		// value of textDocument/rename, java/getRefactorEdit and friends. Not to be
		// confused with workspace.applyEdit, the unrelated permission for jdtls to
		// push an edit at clide on its own initiative: that one is deliberately
		// still not declared (see CLAUDE.md, "Known limitations").
		//
		// documentChanges is what turns the answer from the legacy shape (a bare
		// uri -> TextEdit[] map, which can only ever change file *contents*) into an
		// ordered list that may also carry resource operations. Without
		// resourceOperations alongside it, jdtls has no way to express the file
		// rename that goes with renaming a public class: renaming Square to
		// Rectangle would leave "public class Rectangle" sitting in Square.java -
		// exactly the PublicClassMustMatchFileName situation jdtls would then try to
		// repair behind clide's back through workspace/applyEdit. Declaring this is
		// therefore also what keeps clide out of that hole, rather than having to
		// climb out of it afterwards.
		//
		// normalizesLineEndings false says clide writes back the newText it is
		// given, byte for byte: WorkspaceEdit.applyTo() splices into the file's
		// existing content and never rewrites terminators it was not asked about.
		//
		// failureHandling "abort": if jdtls cannot compute the whole edit, clide
		// wants no edit at all rather than a half-applied refactoring - the
		// transaction can undo a bad write, but only a client that knows one
		// happened would think to.
		final Monomorphic workspaceEditCapabilities = Monomorphic.mapBuilder() //
				.putBoolean("documentChanges", true) //
				.putBoolean("normalizesLineEndings", false) //
				.putList("resourceOperations", List.of(Monomorphic.createString("create"),
						Monomorphic.createString("rename"), Monomorphic.createString("delete"))) //
				.putString("failureHandling", "abort") //
				.build();
		final Monomorphic workspaceCapabilities = Monomorphic.mapBuilder() //
				.put("workspaceEdit", workspaceEditCapabilities) //
				.build();
		final Monomorphic capabilities = Monomorphic.mapBuilder() //
				.put("textDocument", textDocumentCapabilities) //
				.put("workspace", workspaceCapabilities) //
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
	 * The "test" scope only differs from "runtime" when the test source folders are
	 * marked as such in .classpath - see
	 * EclipseDescriptorBuilder.buildDotClasspath().
	 */
	public List<String> testClasspath() throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic result = executeWorkspaceCommand("java.project.getClasspaths",
				List.of(Monomorphic.createString(filesRepository.projectUri()),
						Monomorphic.createString("{\"scope\":\"test\"}")),
				60);
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
	 * URIs of the java projects jdtls holds - one for a plain checkout, several for
	 * a multi-module repository.
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
