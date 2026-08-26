package clide.jdtls;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clide.command.answer.ErrorCode;
import clide.core.Delta;
import clide.core.FilesRepository;
import clide.core.Md5Repository;
import clide.core.Monomorphic;
import clide.core.PositionException;
import clide.core.Snapshot;
import clide.edit.WorkspaceEdit;
import clide.model.CodeLocation;
import clide.model.Diagnostic;
import clide.model.DiagnosticsReport;
import clide.model.Listing;
import clide.model.NarrowableMethod;
import clide.model.Position;
import clide.model.SymbolHit;
import clide.model.TypeCandidate;

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
	 * The tree as jdtls was last told it stands - moved by a build and by a
	 * plain notification alike (see refreshChangedFiles()), which is why it is
	 * named after synchronisation rather than after builds.
	 */
	private Snapshot syncedSnapshot = Snapshot.empty();

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
	 * found them - or removes clide's own if there was nothing to restore. Must
	 * still be called only once build() (the initial one, right after start())
	 * has returned - restoring any earlier would risk a race against jdtls still
	 * reading the files it was just handed - but no longer right after that
	 * either: see EclipseProjectFiles' class doc for why this is now called only
	 * from ClideDaemon.shutdown() on the success path (ClideDaemon.run() itself
	 * still calls this immediately, but only if start()/build() failed). A no-op
	 * if start() never staged anything (e.g. this session was never actually
	 * started).
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
		syncedSnapshot = Snapshot.build(filesRepository);
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
	 * Every .java file that reads differently now than it did the last time
	 * jdtls was told about the tree - what its model does <i>not</i> know about
	 * the project as it currently stands on disk.
	 *
	 * "Since the last sync", not "since the last build", and the distinction is
	 * the point: the model is brought back in step by a plain
	 * workspace/didChangeWatchedFiles notification as well as by a build (see
	 * refreshChangedFiles()), and both move the mark. Naming this after builds
	 * only would make every notified-but-unbuilt change look outstanding
	 * forever.
	 *
	 * Costs one full-project file scan - md5 over every .java file, the same
	 * scan a rebuild pays; about 180 ms on the PlantUML checkout, cache-warm, on
	 * two cores. Reads nothing back from jdtls.
	 */
	public Delta changesSinceLastSync() throws IOException {
		return Snapshot.build(filesRepository).compareWithPreviousSnapshot(syncedSnapshot);
	}

	/**
	 * Brings jdtls' model back in step with the tree on disk, and returns how
	 * many files had to be reported. Zero means there was nothing to say.
	 *
	 * Needed because jdtls' model is not a view of the filesystem: it is an
	 * Eclipse workspace, which only learns of a change made outside its own
	 * editing session when someone tells it. clide never opens files
	 * (textDocument/didOpen) - it builds the whole project instead, on purpose,
	 * because opening PlantUML's 3600 files one by one takes minutes (see
	 * JDTLS.md), so nothing else here would ever tell jdtls a file moved on.
	 *
	 * <b>This notification alone is enough</b>, and that is worth stating
	 * plainly because it was not obvious and had to be measured (see JDTLS.md).
	 * Sending it, with no build of any kind afterwards, was verified to make
	 * jdtls answer correctly about a file created since the last build, an
	 * existing file edited to gain a reference, and a deleted file; to lift the
	 * "Resource ... is out of sync with file system" refusal textDocument/rename
	 * answers otherwise; and to refresh the <i>diagnostics</i> too, in both
	 * directions - an error introduced after the build shows up, and the same
	 * error corrected disappears - because Eclipse auto-builds what a resource
	 * change touches and publishes the result on its own.
	 *
	 * Which files to report is Snapshot's business: the snapshot of the last
	 * sync, compared with one of the tree as it stands now. That fresh snapshot
	 * then <i>becomes</i> the sync mark, which is what keeps the next caller
	 * from reporting the very same files again.
	 */
	public int refreshChangedFiles() throws IOException {
		final Snapshot live = Snapshot.build(filesRepository);
		final Delta delta = live.compareWithPreviousSnapshot(syncedSnapshot);
		if (delta.size() == 0)
			return 0;

		client.notify("workspace/didChangeWatchedFiles",
				Monomorphic.mapBuilder().putList("changes", delta.fileEvents()).build());

		// Recorded only once the notification is actually out: a throw above must
		// leave the mark where it was, or the files jdtls was never told about
		// would count as synced and never be reported again.
		syncedSnapshot = live;

		// A one-way notification: jdtls says nothing when it is done. Measured,
		// though, on PlantUML and on a toy project alike: the first request sent
		// after this one comes back with the *new* answer, taking three to five
		// times longer than the requests after it - jdtls holds it behind the
		// resource refresh rather than answering against the old model. So what
		// protects the next command is the ordering, not this pause; it is kept
		// short, as a margin against a scheduling that observation cannot promise
		// will always work out that way.
		try {
			Thread.sleep(200);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return delta.size();
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
	 * textDocument/prepareRename: whether jdtls will rename the symbol at
	 * position at all, asked before anything is computed.
	 *
	 * Worth its own round trip rather than being folded into rename(). A rename
	 * jdtls declines comes back from textDocument/rename as an *empty*
	 * WorkspaceEdit - indistinguishable, at that point, from a symbol that
	 * genuinely has no occurrence to change. prepareRename separates the two
	 * while it is still cheap to say so, which is the difference between "clide
	 * refuses to rename a keyword" and "clide renamed a keyword, 0 files
	 * changed".
	 *
	 * Both ways of declining are treated the same: jdtls answers a JSON-RPC
	 * error for some non-renameable spots and a null result for others, and the
	 * distinction is not one a caller could act on.
	 */
	public boolean canRename(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/prepareRename",
				JdtlsResponses.positionParams(fileOf(position), position.line(), position.column()), 30);
		if (JdtlsResponses.errorOf(response) != null)
			return false;

		return response.getOrNull("result").isMap();
	}

	/**
	 * textDocument/rename: what would have to change, everywhere in the project,
	 * for the symbol at position to be called newName instead.
	 *
	 * Computes and returns; writes nothing. Applying is
	 * WorkspaceEdit.applyTo()'s job, and keeping the two apart is what lets a
	 * caller refuse the whole thing after seeing it - a rename that touches a
	 * file it should not, or an edit clide cannot read, costs nothing to
	 * discard here.
	 *
	 * jdtls answers against the model of the last build(). Nothing in this
	 * method checks that the model still matches the disk, and it cannot: the
	 * question is about every file at once, not about the one position it was
	 * given. The caller establishes that first (see Snapshot), or the edit
	 * returned is a precise description of a project that no longer exists.
	 */
	public WorkspaceEdit rename(final Position position, final String newName)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/rename",
				JdtlsResponses.renameParams(fileOf(position), position.line(), position.column(), newName), 60);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/rename failed: " + error);

		return WorkspaceEdits.parse(response.getOrNull("result"), filesRepository.getProjectRoot());
	}

	/**
	 * workspace/willRenameFiles: what jdtls computes should change elsewhere in
	 * the project when the file at oldFile is about to become newFile - the
	 * same request/response idea as rename(), keyed off a file move rather than
	 * a symbol name. See MoveClassCommand.
	 *
	 * Computes and returns; writes nothing and does not perform the move
	 * itself, exactly like rename() does not write either - applying is still
	 * WorkspaceEdit.applyTo()'s job. What the caller gets back is text edits
	 * only: jdtls' own answer never includes a resource-rename operation for
	 * oldFile itself, just an in-place edit of its own package declaration
	 * (still addressed at the OLD uri, since this request fires before the
	 * physical move) and the import statements of every cross-package importer
	 * jdtls could find - confirmed empirically, see HISTORY.md. The caller
	 * appends its own ResourceOperation.rename() for the physical move before
	 * calling applyTo().
	 *
	 * A file relying on the moved type through same-package implicit
	 * visibility, without an explicit import, is not found by this request at
	 * all - a confirmed limitation of jdtls' own refactor, not of this method.
	 * See MoveClassCommand's Manual for how that is surfaced.
	 */
	public WorkspaceEdit willRenameFile(final Path oldFile, final Path newFile)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("workspace/willRenameFiles",
				JdtlsResponses.willRenameFilesParams(oldFile.toUri().toString(), newFile.toUri().toString()), 60);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("workspace/willRenameFiles failed: " + error);

		return WorkspaceEdits.parse(response.getOrNull("result"), filesRepository.getProjectRoot());
	}

	/**
	 * The name of every OTHER top-level type declared in the same file as the
	 * one position names - empty when position's file declares exactly one
	 * top-level type. Only the root of the documentSymbol tree is scanned, with
	 * no recursion into any node's children: a nested type therefore never
	 * matches as "position's own node" and always falls through to the
	 * IOException below, which is the point - move_class only moves a
	 * top-level type, and this is how it tells the two cases apart. See
	 * MoveClassCommand.
	 */
	public List<String> siblingTopLevelTypeNames(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = fileOf(position).toUri().toString();
		final List<Monomorphic> roots = JdtlsResponses.documentSymbols(client, uri);

		boolean foundSelf = false;
		final List<String> siblings = new ArrayList<>();
		for (final Monomorphic node : roots) {
			if (node.isMap() == false || JdtlsResponses.isTypeKind(node) == false)
				continue;

			if (isMatchingTypeNode(node, position.name(), position.line() - 1)) {
				foundSelf = true;
				continue;
			}

			final String name = withoutTypeParameters(node.getOrNull("name"));
			if (name != null)
				siblings.add(name);
		}

		if (foundSelf == false)
			throw new IOException("'" + position.name() + "' at line " + position.line() + " of " + position.path()
					+ " is not declared at the top level of its file (move_class only moves top-level types)");

		return siblings;
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

	/** A public method declaration is refused this - the JVM invokes it, nothing in the project ever will. */
	private static final String ENTRY_POINT_METHOD_NAME = "main";

	/** Whole-word "public" on a member's own declaration line - see isDeclaredPublic(). */
	private static final Pattern PUBLIC_MODIFIER = Pattern.compile("\\bpublic\\b");

	/**
	 * list_could_be_private: every <b>public</b>, directly-declared method of
	 * the class/interface/enum named position.name() whose every real usage -
	 * textDocument/references, same request find_reference sends - stays
	 * inside that type's own source range. A method with zero usages at all
	 * counts too (NarrowableMethod.neverCalled()), on the same footing as one
	 * only ever called from inside - see NarrowableMethod's own doc for why
	 * that overload/override matches are still reported rather than dropped.
	 *
	 * "Inside that type's own source range" is checked against the type
	 * node's own documentSymbol "range" (its whole declaration, body
	 * included - unlike the "selectionRange" &lt;position&gt; itself is built
	 * from), not merely "same file": a second top-level type sharing the file
	 * is correctly treated as external, and a usage from a nested type
	 * declared inside this one is correctly treated as internal, since a
	 * nested type's own textual span sits inside its enclosing type's range.
	 * The one case this does not get right is the reverse - a usage from the
	 * *enclosing* type of the one actually inspected, when &lt;position&gt;
	 * names a nested type - since Java's own nest-based access control allows
	 * that too but that outer usage sits outside the nested type's own range.
	 * A known, narrow limitation, not attempted here.
	 *
	 * public static void main(String[] args) is always excluded, unconditionally: the
	 * JVM launcher invokes it, so it would otherwise pass with zero project
	 * usages found and be flagged for narrowing a call clide never sees, which
	 * would break "java -jar clide.jar" itself if actually applied.
	 */
	public List<NarrowableMethod> narrowableMethods(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = fileOf(position).toUri().toString();
		final Monomorphic typeNode = findTypeNode(JdtlsResponses.documentSymbols(client, uri), position.name(),
				position.line() - 1);
		if (typeNode.isMap() == false)
			throw new IOException("No class/interface/enum named '" + position.name() + "' declared at line "
					+ position.line() + " of " + position.path()
					+ " (list_could_be_private only inspects types, not methods/fields)");

		final int bodyStartLine = JdtlsResponses.oneBased(JdtlsResponses.lineOf(JdtlsResponses.startOf(typeNode.getOrNull("range"))));
		final int bodyEndLine = JdtlsResponses.oneBased(JdtlsResponses.lineOf(JdtlsResponses.endOf(typeNode.getOrNull("range"))));

		final Map<String, List<String>> inherited = inheritedMethodSignatures(position);

		final List<NarrowableMethod> candidates = new ArrayList<>();
		for (final SymbolHit hit : collectMembers(uri, JdtlsResponses.childrenOf(typeNode))) {
			if ("method".equals(hit.kind()) == false || hit.location() == null)
				continue;

			if (isDeclaredPublic(hit.location().lineText()) == false)
				continue;

			final String baseName = baseNameOf(hit.name());
			if (ENTRY_POINT_METHOD_NAME.equals(baseName))
				continue;

			final List<CodeLocation> references = goToPosition("textDocument/references", hit.location().position(),
					Monomorphic.mapBuilder().putBoolean("includeDeclaration", false).build());
			if (calledFromOutside(references, position.path(), bodyStartLine, bodyEndLine))
				continue;

			final String signature = signatureOf(baseName, hit.name());
			candidates.add(new NarrowableMethod(hit.location(), inherited.getOrDefault(signature, List.of()),
					references.isEmpty()));
		}

		return candidates;
	}

	/** Whether any of references falls outside [bodyStartLine, bodyEndLine] of declaringPath - see narrowableMethods(). */
	private static boolean calledFromOutside(final List<CodeLocation> references, final String declaringPath,
			final int bodyStartLine, final int bodyEndLine) {
		for (final CodeLocation reference : references) {
			final Position at = reference.position();
			if (declaringPath.equals(at.path()) == false || at.line() < bodyStartLine || at.line() > bodyEndLine)
				return true;
		}
		return false;
	}

	/**
	 * Every method signature (see signatureOf()) declared by position's own
	 * supertypes and directly/indirectly implemented interfaces - walked all
	 * the way up, not just the one hop findSupertypes() itself takes - mapped
	 * to the simple name(s) of the type(s) it was found declared on.
	 *
	 * java.lang.Object's own equals/hashCode/toString/clone/finalize are
	 * seeded in unconditionally rather than discovered by the walk: a JDK
	 * type is never a findSupertypes() CodeLocation to begin with -
	 * locationOf() drops anything outside the project, see its own doc - so
	 * without this, an overridden Object method would never be walked to at
	 * all and would wrongly look like a candidate with nothing to override.
	 *
	 * visited guards the walk against revisiting the same type twice - an
	 * interface reachable through two different paths (diamond inheritance),
	 * or position's own starting type reappearing through a self-reference
	 * that should never happen but costs nothing to guard against anyway.
	 */
	private Map<String, List<String>> inheritedMethodSignatures(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Map<String, List<String>> signatures = new LinkedHashMap<>();
		for (final String objectMethod : List.of(signatureOf("equals", "equals(Object)"),
				signatureOf("hashCode", "hashCode()"), signatureOf("toString", "toString()"),
				signatureOf("clone", "clone()"), signatureOf("finalize", "finalize()")))
			signatures.put(objectMethod, new ArrayList<>(List.of("Object")));

		final Set<String> visited = new HashSet<>();
		visited.add(visitKey(position));

		final Deque<Position> toWalk = new ArrayDeque<>();
		toWalk.add(position);
		while (toWalk.isEmpty() == false) {
			final List<CodeLocation> supertypes;
			try {
				supertypes = findSupertypes(toWalk.poll());
			} catch (final NotApplicableException e) {
				continue; // not expected to happen - see the class-only walk below - but not fatal either
			}

			for (final CodeLocation supertype : supertypes) {
				final Position supertypePosition = supertype.position();
				if (visited.add(visitKey(supertypePosition)) == false)
					continue;

				for (final SymbolHit member : listMembers(supertypePosition))
					if ("method".equals(member.kind()))
						signatures.computeIfAbsent(signatureOf(baseNameOf(member.name()), member.name()),
								ignored -> new ArrayList<>()).add(supertypePosition.name());

				toWalk.add(supertypePosition);
			}
		}

		return signatures;
	}

	private static String visitKey(final Position position) {
		return position.path() + ":" + position.line() + ":" + position.column();
	}

	/** Whole-word "public" somewhere on lineText - the one thing documentSymbol never carries, see SymbolHit. */
	private static boolean isDeclaredPublic(final String lineText) {
		return PUBLIC_MODIFIER.matcher(lineText).find();
	}

	/** "goToPosition" out of "goToPosition(String, Position)" - see countTopLevelParams()'s own doc for the shape read here. */
	private static String baseNameOf(final String rawName) {
		final int paren = rawName.indexOf('(');
		return paren < 0 ? rawName : rawName.substring(0, paren);
	}

	/**
	 * "goToPosition/2" for "goToPosition(String, Position)" - a signature two
	 * different methods (this class' own candidate, a supertype's member)
	 * compare equal on when they share a name and a parameter count. Never
	 * compiled into a Pattern or reused as a Map key type of its own: a plain
	 * String stays trivially comparable/hashable, and nothing here ever needs
	 * to parse a signature back apart.
	 */
	private static String signatureOf(final String baseName, final String rawName) {
		final int paren = rawName.indexOf('(');
		final int arity = paren < 0 ? 0 : countTopLevelParams(rawName.substring(paren + 1, rawName.length() - 1));
		return baseName + "/" + arity;
	}

	/**
	 * The number of top-level parameters in params - same shape and same
	 * counting rule as PositionParser.countTopLevelParams(), duplicated here
	 * rather than shared across clide.core/clide.jdtls: a few lines of pure
	 * text counting is a smaller dependency than a cross-package coupling for
	 * two callers that otherwise share nothing (see ResearchRegexCommand's
	 * displayPath(), duplicated in RemoveUnusedImportsCommand for the same
	 * reason).
	 */
	private static int countTopLevelParams(final String params) {
		if (params.isBlank())
			return 0;

		int depth = 0;
		int count = 1;
		for (int i = 0; i < params.length(); i++) {
			final char c = params.charAt(i);
			if (c == '<')
				depth++;
			else if (c == '>')
				depth--;
			else if (c == ',' && depth == 0)
				count++;
		}
		return count;
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

	// ------------------------------------------------------------------
	// Call hierarchy and type hierarchy
	// ------------------------------------------------------------------

	/**
	 * textDocument/prepareCallHierarchy + callHierarchy/incomingCalls: every
	 * method/constructor that directly calls the one at position - one hop,
	 * not the whole call graph. Each entry is the *caller's own declaration*
	 * (name, not one particular call site inside it), so it is directly
	 * chainable into another findCallers() to walk up a level further - the
	 * same way the Lua example in CLAUDE.md already walks find_reference in a
	 * loop.
	 *
	 * @throws NotApplicableException position does not name a method or
	 *                                 constructor at all (see
	 *                                 prepareCallHierarchy()). A method with
	 *                                 no caller is a different, and entirely
	 *                                 valid, answer: an empty list.
	 */
	public List<CodeLocation> findCallers(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return callHierarchyLocations(position, "callHierarchy/incomingCalls", "from");
	}

	/**
	 * textDocument/prepareCallHierarchy + callHierarchy/outgoingCalls: every
	 * method/constructor that the one at position directly calls - the
	 * counterpart findCallers() has no equivalent for today (find_reference
	 * answers "who uses this symbol", never "what does this one call").
	 *
	 * @throws NotApplicableException see findCallers().
	 */
	public List<CodeLocation> findCallees(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return callHierarchyLocations(position, "callHierarchy/outgoingCalls", "to");
	}

	/**
	 * Shared by findCallers()/findCallees(): both requests take the same
	 * {"item": <the CallHierarchyItem prepareCallHierarchy() resolved>} params
	 * and answer a flat array of {"from"|"to": CallHierarchyItem, "fromRanges":
	 * Range[]} - only the LSP method name and which of those two fields names
	 * the *other* item differ. fromRanges (where, inside that other method,
	 * the call itself sits) is read by nothing here: what a caller can chain
	 * into another findCallers()/findCallees() is the *method's* position, not
	 * one particular call site inside it.
	 */
	private List<CodeLocation> callHierarchyLocations(final Position position, final String lspMethod,
			final String itemField) throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic item = prepareCallHierarchy(position);

		final Monomorphic response = client.request(lspMethod, Monomorphic.mapBuilder().put("item", item).build(),
				30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException(lspMethod + " failed: " + error);

		// LinkedHashSet, not a plain list: jdtls reports one entry per call SITE,
		// not one per caller - a method that calls position twice comes back as
		// two separate {"from"|"to": ..., "fromRanges"|"toRanges": [...]} entries,
		// both resolving (via callHierarchyItemLocation()) to the exact same
		// caller/callee declaration. find_callers/find_callees answer "who calls
		// this" - a set of methods, not a multiset of call sites (that finer
		// granularity, one row per site, is what find_reference is for) - so a
		// caller that calls twice is still one entry here, and the "N location(s)"
		// count reflects that. Insertion order preserved (jdtls' own), same as
		// everywhere else that reports "in server order".
		final Set<CodeLocation> located = new LinkedHashSet<>();
		for (final Monomorphic call : response.getOrNull("result").elementsOf())
			if (call.isMap()) {
				final CodeLocation location = callHierarchyItemLocation(call.getOrNull(itemField));
				if (location != null)
					located.add(location);
			}

		return List.copyOf(located);
	}

	/**
	 * textDocument/prepareCallHierarchy: resolves position to the single
	 * CallHierarchyItem jdtls associates with it. clide's own &lt;position&gt;
	 * notation already pins a request down to one exact occurrence (name,
	 * whole word, at that column of that line), so there is nothing left for
	 * this step to disambiguate between - the first (and, in every case
	 * observed, only) item prepareCallHierarchy answers with is the one asked
	 * about.
	 *
	 * An empty (or null) result is jdtls saying position does not name
	 * something callable at all - a field, a variable, a type - and is what
	 * NotApplicableException exists to report distinctly from every other
	 * failure this method (and callHierarchyLocations() after it) can raise.
	 */
	private Monomorphic prepareCallHierarchy(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/prepareCallHierarchy",
				JdtlsResponses.positionParams(fileOf(position), position.line(), position.column()), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/prepareCallHierarchy failed: " + error);

		final List<Monomorphic> items = response.getOrNull("result").elementsOf();
		if (items.isEmpty())
			throw new NotApplicableException("'" + position.name() + "' at line " + position.line() + " of "
					+ position.path() + " is not a method or constructor - find_callers/find_callees only work on those");

		return items.get(0);
	}

	/**
	 * textDocument/prepareTypeHierarchy + typeHierarchy/supertypes: the
	 * direct superclass and/or interfaces of the class/interface/enum at
	 * position - one hop, not the whole hierarchy up to Object. Chainable
	 * into another findSupertypes() the same way findCallers() is - see its
	 * doc.
	 *
	 * Distinct from find_implementation("type", ...): that one answers "every
	 * class that implements/extends this, anywhere below it, all at once";
	 * this answers "what does this directly extend/implement, one level up".
	 *
	 * @throws NotApplicableException position does not name a type at all
	 *                                 (see prepareTypeHierarchy()). A type
	 *                                 with no supertype beyond Object -
	 *                                 jdtls' own choice whether to report
	 *                                 Object itself - is a different, and
	 *                                 entirely valid, answer.
	 */
	public List<CodeLocation> findSupertypes(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return typeHierarchyLocations(position, "typeHierarchy/supertypes");
	}

	/**
	 * textDocument/prepareTypeHierarchy + typeHierarchy/subtypes: the direct
	 * subclasses/implementors of the class/interface/enum at position - one
	 * hop down, the reverse of findSupertypes(). See its doc for how this
	 * differs from find_implementation("type", ...).
	 *
	 * @throws NotApplicableException see findSupertypes().
	 */
	public List<CodeLocation> findSubtypes(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return typeHierarchyLocations(position, "typeHierarchy/subtypes");
	}

	private List<CodeLocation> typeHierarchyLocations(final Position position, final String lspMethod)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic item = prepareTypeHierarchy(position);

		final Monomorphic response = client.request(lspMethod, Monomorphic.mapBuilder().put("item", item).build(),
				30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException(lspMethod + " failed: " + error);

		final List<CodeLocation> located = new ArrayList<>();
		for (final Monomorphic typeItem : response.getOrNull("result").elementsOf()) {
			final CodeLocation location = typeHierarchyItemLocation(typeItem);
			if (location != null)
				located.add(location);
		}

		return located;
	}

	/** Same role as prepareCallHierarchy(), for typeHierarchy/supertypes|subtypes. */
	private Monomorphic prepareTypeHierarchy(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/prepareTypeHierarchy",
				JdtlsResponses.positionParams(fileOf(position), position.line(), position.column()), 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("textDocument/prepareTypeHierarchy failed: " + error);

		final List<Monomorphic> items = response.getOrNull("result").elementsOf();
		if (items.isEmpty())
			throw new NotApplicableException("'" + position.name() + "' at line " + position.line() + " of "
					+ position.path() + " is not a class, interface, or enum - find_supertypes/find_subtypes only work on those");

		return items.get(0);
	}

	/**
	 * A TypeHierarchyItem's own position - reusing locationOf() the way
	 * memberOf() already does for a documentSymbol child (see its doc): a
	 * TypeHierarchyItem carries no plain Location of its own (uri+range
	 * together as one field), so a synthetic one is built from the item's own
	 * "uri" and "selectionRange" - the type name's own token, not its whole
	 * declaration/body. Verified empirically (self-test against clide's own
	 * FindCallersCommand/Command hierarchy) that a TypeHierarchyItem's
	 * selectionRange is exactly that: unlike a CallHierarchyItem's (see
	 * callHierarchyItemLocation() for why that one needs a second request).
	 */
	private CodeLocation typeHierarchyItemLocation(final Monomorphic item) {
		if (item.isMap() == false)
			return null;

		final Monomorphic location = Monomorphic.mapBuilder() //
				.put("uri", item.getOrNull("uri")) //
				.put("range", item.getOrNull("selectionRange")) //
				.build();

		return locationOf(location);
	}

	/**
	 * A CallHierarchyItem's own declaration position - NOT read off its own
	 * "selectionRange" the way typeHierarchyItemLocation() reads a
	 * TypeHierarchyItem's. Verified empirically (self-test against clide's
	 * own CommandResults.rejectUnlessOneOf() and its callers): jdtls (Eclipse
	 * JDT LS) sets a call hierarchy item's "selectionRange" to the call site
	 * itself - the same span as its sibling field "fromRanges"/"toRanges" -
	 * rather than to the calling/called method's own name, which is what
	 * clide's &lt;position&gt; notation needs to stay chainable into another
	 * find_callers/find_callees/hover/find_reference.
	 *
	 * What jdtls does report correctly on the item is "range": the whole
	 * enclosing declaration, annotations included. That is enough to recover
	 * the real position without guessing: the item's own file's
	 * textDocument/documentSymbol tree (the same one list_members already
	 * reads) has its own member node for that exact declaration, at the same
	 * start line jdtls itself computed for "range" - no name/overload
	 * matching needed, since the line alone already pins it down uniquely -
	 * and that member node's own selectionRange is trustworthy (it is exactly
	 * what memberOf()/list_members already build a CodeLocation from).
	 *
	 * Returns null when no such member node is found (dropped rather than
	 * guessed at, the same tolerance locationOf() already has for a location
	 * outside the project) - not expected to ever happen for a real jdtls
	 * answer, but "jdtls reported a line documentSymbol does not corroborate"
	 * is exactly the kind of surprise this exists to not paper over.
	 */
	private CodeLocation callHierarchyItemLocation(final Monomorphic item)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		if (item.isMap() == false)
			return null;

		final String uri = JdtlsResponses.uriOf(item);
		final int zeroBasedLine = JdtlsResponses.lineOf(JdtlsResponses.startOf(JdtlsResponses.rangeOf(item)));
		final Monomorphic memberNode = findMemberNodeStartingAt(JdtlsResponses.documentSymbols(client, uri),
				zeroBasedLine);
		if (memberNode.isMap() == false)
			return null;

		final Monomorphic location = Monomorphic.mapBuilder() //
				.put("uri", item.getOrNull("uri")) //
				.put("range", memberNode.getOrNull("selectionRange")) //
				.build();

		return locationOf(location);
	}

	/**
	 * Recursively searches a documentSymbol tree (nodes, and each node's own
	 * "children" - see findTypeNode(), which does the same walk for a
	 * type-kind node) for a method/constructor node whose own "range" starts
	 * on exactly zeroBasedLine. Returns the first match found (depth-first),
	 * or null.
	 */
	private Monomorphic findMemberNodeStartingAt(final List<Monomorphic> nodes, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false)
				continue;

			if (isCallableKind(node)
					&& JdtlsResponses.lineOf(JdtlsResponses.startOf(node.getOrNull("range"))) == zeroBasedLine)
				return node;

			final Monomorphic foundInChildren = findMemberNodeStartingAt(JdtlsResponses.childrenOf(node),
					zeroBasedLine);
			if (foundInChildren.isMap())
				return foundInChildren;
		}
		return Monomorphic.createNull();
	}

	/** Whether node is "method/constructor"-shaped - SymbolKind 6/9, see symbolKindLabel(). */
	private boolean isCallableKind(final Monomorphic node) {
		final int kind = (int) node.getOrNull("kind").longOrDefault(-1);
		return kind == 6 || kind == 9;
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

	/**
	 * workspace/symbol restricted to class/interface/enum-kind hits whose name -
	 * type parameters stripped, exactly as findTypeNode() already does for
	 * documentSymbol - equals simpleName exactly, each paired with jdtls' own
	 * containerName (the enclosing type's fully qualified name for a nested
	 * class, the package for a top-level one). The raw material PositionParser's
	 * "Classe"/"Outer.Inner seule" and "Classe::membre" resolution (see
	 * SYMBOLS.md) filters and disambiguates from.
	 *
	 * Unlike findSymbol(), an <b>exact</b> match only - never fuzzy/camelCase.
	 * workspace/symbol's own loose matching is a discovery aid for a human
	 * typing a partial query; a &lt;position&gt; token names a class the caller
	 * already chose deliberately; matching anything looser would silently widen
	 * what a token can resolve to.
	 *
	 * A hit outside the project (isInProject() false) is dropped rather than
	 * kept unsigned, the same way locationOf() already drops one for
	 * find_declaration/find_implementation - see its own doc.
	 */
	public List<TypeCandidate> findTypesNamed(final String simpleName)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder().putString("query", simpleName).build();

		final Monomorphic response = client.request("workspace/symbol", params, 30);
		final Monomorphic error = JdtlsResponses.errorOf(response);
		if (error != null)
			throw new IOException("workspace/symbol failed: " + error);

		final List<TypeCandidate> candidates = new ArrayList<>();
		for (final Monomorphic item : response.getOrNull("result").elementsOf()) {
			if (item.isMap() == false || JdtlsResponses.isTypeKind(item) == false)
				continue;
			if (simpleName.equals(withoutTypeParameters(item.getOrNull("name"))) == false)
				continue;

			final Monomorphic location = item.getOrNull("location");
			if (location.isMap() == false)
				continue;
			final CodeLocation located = locationOf(location);
			if (located == null)
				continue; // outside the project - see locationOf()

			final String containerName = item.getOrNull("containerName").stringOrNull();
			candidates.add(new TypeCandidate(containerName == null ? "" : containerName, located));
		}
		return candidates;
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
	 * Eclipse's own problem id for "The import ... is never used" - empirically
	 * confirmed (see HISTORY.md) by printing a raw diagnostic for a deliberately
	 * unused import and reading its "code" field back. Not documented anywhere
	 * as a stable contract, but it is what jdt.core actually sends, and matching
	 * on it is far more robust than matching jdt's own message wording, which
	 * is free to change or be localized.
	 */
	private static final String UNUSED_IMPORT_PROBLEM_ID = "268435844";

	/**
	 * Every file the last build flagged at least one unused import in, mapped to
	 * the 1-based line(s) jdtls flagged - project-relative, forward-slash
	 * paths, the same shape diagnosticsReport() prints. remove_unused_imports'
	 * only way of finding candidates without parsing any Java itself.
	 *
	 * Filtered by UNUSED_IMPORT_PROBLEM_ID rather than by severity: an unused
	 * import is a warning, and plenty of other warnings are too, so severity
	 * alone cannot tell them apart.
	 *
	 * Reads the same diagnosticsByUri every print_diagnostics/rebuild already
	 * read - nothing here triggers a build of its own, so this only ever
	 * answers for the last build CommandDispatcher already required to be
	 * fresh (see Command.needsFreshModel()).
	 */
	public Map<String, List<Integer>> unusedImportLines() {
		final Map<String, List<Integer>> result = new TreeMap<>();
		for (final Map.Entry<String, List<Monomorphic>> entry : diagnosticsByUri.entrySet()) {
			final List<Integer> lines = new ArrayList<>();
			for (final Monomorphic diagnostic : entry.getValue()) {
				if (isUnusedImportDiagnostic(diagnostic) == false)
					continue;

				final int zeroBasedLine = JdtlsResponses.lineOf(JdtlsResponses.startOf(diagnostic.getOrNull("range")));
				if (zeroBasedLine != -1)
					lines.add(zeroBasedLine + 1);
			}

			if (lines.isEmpty() == false)
				result.put(shortName(entry.getKey()), lines);
		}

		return result;
	}

	/**
	 * Whether diagnostic is jdt's own "unused import" warning. The one raw
	 * sample captured while confirming UNUSED_IMPORT_PROBLEM_ID carried "code"
	 * as a JSON string, which is what the string comparison below expects - a
	 * JSON number is also accepted, defensively, since nothing in the LSP spec
	 * promises one shape over the other for an opaque "code".
	 */
	private static boolean isUnusedImportDiagnostic(final Monomorphic diagnostic) {
		final Monomorphic code = diagnostic.getOrNull("code");
		final String asString = code.isNumber() ? String.valueOf(code.longOrDefault(0)) : code.stringOrNull();
		return UNUSED_IMPORT_PROBLEM_ID.equals(asString);
	}

	/**
	 * Eclipse's own problem id for "X cannot be resolved to a type" -
	 * empirically confirmed the same way UNUSED_IMPORT_PROBLEM_ID was: a raw
	 * diagnostic printed for a deliberately unresolved reference carried
	 * {"code":"16777218","message":"ScratchOther cannot be resolved to a
	 * type",...}. move_class's own auto-import pass (see MoveClassCommand) is
	 * the one consumer.
	 */
	private static final String UNRESOLVED_TYPE_PROBLEM_ID = "16777218";

	/** jdt's own message shape for UNRESOLVED_TYPE_PROBLEM_ID - group 1 is the unresolved simple name. */
	private static final Pattern UNRESOLVED_TYPE_MESSAGE = Pattern.compile("^(\\w+) cannot be resolved to a type$");

	/**
	 * Every file the last build flagged with "&lt;simpleClassName&gt; cannot be
	 * resolved to a type" - project-relative, forward-slash paths, sorted and
	 * deduplicated (a file carrying more than one such diagnostic is only
	 * listed once). move_class's own way of finding, after applying its own
	 * edit, exactly which files still cannot see the class it just moved - see
	 * MoveClassCommand.
	 *
	 * Filtered by UNRESOLVED_TYPE_PROBLEM_ID *and* by the message actually
	 * naming simpleClassName: the problem id alone fires for any unresolved
	 * type anywhere in the project, not only the one this move cares about.
	 *
	 * Reads the same diagnosticsByUri every print_diagnostics/rebuild already
	 * read - nothing here triggers a build of its own; the caller is
	 * responsible for having refreshed the model first.
	 */
	public List<String> filesUnresolvedFor(final String simpleClassName) {
		final Set<String> files = new TreeSet<>();
		for (final Map.Entry<String, List<Monomorphic>> entry : diagnosticsByUri.entrySet())
			for (final Monomorphic diagnostic : entry.getValue())
				if (namesUnresolvedType(diagnostic, simpleClassName))
					files.add(shortName(entry.getKey()));

		return new ArrayList<>(files);
	}

	private static boolean namesUnresolvedType(final Monomorphic diagnostic, final String simpleClassName) {
		final Monomorphic code = diagnostic.getOrNull("code");
		final String asString = code.isNumber() ? String.valueOf(code.longOrDefault(0)) : code.stringOrNull();
		if (UNRESOLVED_TYPE_PROBLEM_ID.equals(asString) == false)
			return false;

		final String message = diagnostic.getOrNull("message").stringOrNull();
		if (message == null)
			return false;

		final Matcher matcher = UNRESOLVED_TYPE_MESSAGE.matcher(message);
		return matcher.matches() && matcher.group(1).equals(simpleClassName);
	}

	/** See stop()'s own doc for where this number comes from. */
	private static final long GRACEFUL_EXIT_TIMEOUT_SECONDS = 30;

	/**
	 * Attempts a graceful LSP shutdown, gives jdtls a real chance to actually
	 * exit on its own in reaction to it, then stops the underlying process
	 * either way.
	 *
	 * The pause before launcher.stop() is not a nicety - see
	 * JdtlsLauncher.awaitExit()'s own doc for the failure mode it exists to
	 * avoid: sending SIGTERM right after the "exit" notification, before jdtls'
	 * own exit() handler finishes saving the workspace, which is exactly what
	 * used to turn a persistent, reused workspace (JdtlsWorkspace) into no
	 * saving at all - the next open would find it unclean and pay to recover
	 * it, wiping out whatever the reuse itself had saved.
	 *
	 * GRACEFUL_EXIT_TIMEOUT_SECONDS (30s) was chosen empirically: a full
	 * IWorkspace.save() on a PlantUML-sized project (3606 files) was measured
	 * completing - process exiting on its own, no signal sent at all - well
	 * within a few seconds; 30s leaves ample room above that measurement
	 * without making "terminate" feel broken on a jdtls that is genuinely
	 * stuck (which still gets the full stop() treatment below once this
	 * returns false).
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

			try {
				launcher.awaitExit(GRACEFUL_EXIT_TIMEOUT_SECONDS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
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
		final Monomorphic fileOperationsCapabilities = Monomorphic.mapBuilder() //
				.putBoolean("willRename", true) //
				.build();
		final Monomorphic workspaceCapabilities = Monomorphic.mapBuilder() //
				.put("workspaceEdit", workspaceEditCapabilities) //
				.put("fileOperations", fileOperationsCapabilities) //
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
