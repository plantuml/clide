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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import clide.core.Symbol;

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

	/**
	 * Directories currentSourceFiles() never walks into - no sources there, and on
	 * a project like PlantUML they hold far more files than the sources do.
	 */
	private static final List<String> SKIPPED_DIRECTORIES = List.of(".git", "bin", "build", "target", "out", "jdtls",
			"node_modules", ".gradle", ".clide");

	private final JdtlsLauncher launcher;
	private final Path projectRoot;
	private LspClient client;
	private Thread notificationThread;
	private volatile boolean ready;
	private final Map<String, List<Truc>> diagnosticsByUri = new ConcurrentHashMap<>();

	/**
	 * Absolute path -&gt; mtime, as of the end of the last build() - see
	 * refreshChangedFiles().
	 */
	private final Map<String, Long> sourceFileTimestamps = new ConcurrentHashMap<>();

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

		final Truc response = client.request("initialize", initializeParams(), 120);
		if (response.containsKey("error"))
			throw new IOException("jdtls initialize failed: " + response.getObject("error"));

		client.notify("initialized", new Truc());
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
		// Snapshotted before the build, not after: a file edited while the build
		// is running would otherwise be recorded with its new timestamp and
		// counted as already built, and the next rebuild would skip it.
		snapshotSourceFiles();
		diagnosticsByUri.clear();
		final Truc response = client.request("java/buildWorkspace", Boolean.TRUE, 300);
		if (response.containsKey("error"))
			throw new IOException("java/buildWorkspace failed: " + response.getObject("error"));

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
		final Map<String, Long> current = currentSourceFiles();
		final List<Truc> events = new ArrayList<>();

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

		final Truc params = new Truc();
		params.putList("changes", events);
		client.notify("workspace/didChangeWatchedFiles", params);

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

	private Truc fileEvent(final String path, final int type) {
		final Truc event = new Truc();
		event.putString("uri", Paths.get(path).toUri().toString());
		event.putLong("type", type);
		return event;
	}

	private void snapshotSourceFiles() {
		try {
			sourceFileTimestamps.clear();
			sourceFileTimestamps.putAll(currentSourceFiles());
		} catch (final IOException e) {
			// Best effort: a failure here just means the next
			// refreshChangedFiles() reports more files than strictly changed.
		}
	}

	/**
	 * Absolute path -&gt; last-modified time of every .java file under the project,
	 * skipping the directories that hold no sources but do hold thousands of files
	 * (.git, build output, the extracted jdtls itself).
	 */
	private Map<String, Long> currentSourceFiles() throws IOException {
		final Map<String, Long> files = new LinkedHashMap<>();
		try (Stream<Path> walk = Files.walk(projectRoot)) {
			walk.filter(path -> path.toString().endsWith(".java")).filter(path -> isSkipped(path) == false)
					.forEach(path -> {
						try {
							files.put(path.toString(), Files.getLastModifiedTime(path).toMillis());
						} catch (final IOException e) {
							// vanished between the walk and the stat - treat as absent
						}
					});
		}
		return files;
	}

	private boolean isSkipped(final Path path) {
		for (final Path segment : projectRoot.relativize(path))
			if (SKIPPED_DIRECTORIES.contains(segment.toString()))
				return true;

		return false;
	}

	/**
	 * Sends lspMethod ("textDocument/definition", "textDocument/typeDefinition", or
	 * "textDocument/implementation") at symbol's position against this session.
	 * Shared by GotoDefinitionCommand, GotoTypeDefinitionCommand and
	 * GotoImplementationCommand - only the LSP method name differs between them.
	 * See the other overload for requests that also need an LSP request-level
	 * "context" object (currently only textDocument/references does).
	 *
	 * symbol is already known to name a real file/line/word - it can only have come
	 * from Symbol.parse(), which validated all of that up front (see
	 * ParamType.SYMBOL, ClideDaemon.validate()) - so no re-validation happens here.
	 *
	 * No textDocument/didOpen is sent first: this relies on jdtls already having
	 * the file in its compiled model from the last build() (see JDTLS.md, section
	 * 0bis) rather than on editor-style open/close tracking. To be revisited if
	 * that turns out not to be enough in practice.
	 *
	 * Returns one formatted "path:line: line content" entry per location in the
	 * response, in server order; an empty list if the response was empty/null.
	 */
	public List<String> goToPosition(final String lspMethod, final Symbol symbol)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		return goToPosition(lspMethod, symbol, null);
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
	public List<String> goToPosition(final String lspMethod, final Symbol symbol, final Truc context)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Truc response = client.request(lspMethod,
				positionParams(symbol.file(), symbol.line(), symbol.column(), context), 30);
		if (response.containsKey("error"))
			throw new IOException(lspMethod + " failed: " + response.getObject("error"));

		return formatLocations(response.getObject("result"));
	}

	/**
	 * textDocument/hover: the signature/Javadoc jdtls knows for symbol itself - as
	 * opposed to goToPosition(), which locates some *other* place (a definition, an
	 * implementation), hover explains this exact symbol where it stands. Returns
	 * jdtls' hover text verbatim (already Markdown, printed as-is - not
	 * reformatted), or "<no hover info>" if jdtls had nothing to say (e.g. the
	 * symbol's type can't be resolved - no matching jar in .clide - or hover just
	 * doesn't apply to this kind of symbol).
	 */
	public String hover(final Symbol symbol) throws IOException, InterruptedException, LspClient.TimeoutException {
		final Truc response = client.request("textDocument/hover",
				positionParams(symbol.file(), symbol.line(), symbol.column()), 30);
		if (response.containsKey("error"))
			throw new IOException("textDocument/hover failed: " + response.getObject("error"));

		return formatHover(response.getObject("result"));
	}

	/**
	 * textDocument/documentSymbol: lists the direct members (methods, fields,
	 * constructors - not further-nested inner types' own members) of the
	 * class/interface/enum named symbol.name(), declared at symbol.line() of
	 * symbol.file() - here symbol picks which type to inspect rather than where to
	 * jump/what to explain. Requires hierarchicalDocumentSymbolSupport (see
	 * initializeParams()) - without declaring it, jdtls falls back to a flat
	 * SymbolInformation[] with no "children" at all, and this could never find any
	 * member.
	 *
	 * Returns one "[kind] path:line: line content" entry per member, in
	 * documentSymbol's own order.
	 */
	public List<String> listMembers(final Symbol symbol)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = symbol.file().toUri().toString();
		final Truc typeNode = findTypeNode(documentSymbols(uri), symbol.name(), symbol.line() - 1);
		if (typeNode == null)
			throw new IOException(
					"No class/interface/enum named '" + symbol.name() + "' declared at line " + symbol.line() + " of "
							+ symbol.file() + " (list_members only inspects types, not methods/fields)");

		return formatMembers(uri, typeNode.getList("children"));
	}

	/** Raw textDocument/documentSymbol tree for uri - empty on any error. */
	private List<Object> documentSymbols(final String uri)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Truc textDocument = new Truc();
		textDocument.putString("uri", uri);
		final Truc params = new Truc();
		params.putTruc("textDocument", textDocument);

		final Truc response = client.request("textDocument/documentSymbol", params, 30);
		if (response.containsKey("error"))
			throw new IOException("textDocument/documentSymbol failed: " + response.getObject("error"));

		return asList(response.getObject("result"));
	}

	/**
	 * textDocument/implementation on a *method*, plus a second pass that recovers
	 * the overrides jdtls silently omits.
	 *
	 * jdtls answers textDocument/implementation on a method by building a JDT
	 * SearchPattern from the method element and running it over the declaring
	 * type's hierarchy scope. That pattern compares parameter types by the *source
	 * spelling* of the declaration, so when the target method declares its own type
	 * parameter - "&lt;SHAPE extends UShape&gt; void draw(SHAPE shape)" - only
	 * overrides that spell the type variable identically match. Two perfectly legal
	 * override forms are therefore dropped without a word:
	 *
	 * - the erasure form, "void draw(UShape shape)" (a subsignature per JLS 8.4.2,
	 * which javac accepts without even an -Xlint warning, and which an
	 * 
	 * @Override annotation does not rescue), and - the renamed form, "&lt;X extends
	 *           UShape&gt; void draw(X shape)".
	 *
	 *           On PlantUML that means 3 of the 25 real overrides of UGraphic.draw
	 *           are reported - the other 22 look like they don't exist. A caller
	 *           trusting the result would conclude the drawing layer has three
	 *           implementations.
	 *
	 *           The recovery pass asks the question jdtls *does* answer correctly:
	 *           textDocument/implementation on the declaring *type* (44/44 correct
	 *           on PlantUML), then reads each subtype's own documentSymbol tree and
	 *           keeps the members declaring a method of the same name and arity.
	 *           Both result sets are unioned rather than one replacing the other -
	 *           each finds cases the other misses (the pass below cannot see a
	 *           subtype jdtls' type search didn't return, and jdtls sees the
	 *           same-spelling overrides directly).
	 *
	 *           Arity, not full signature, is what is compared: reconstructing
	 *           erasure from source text would mean resolving every parameter type
	 *           by hand, which is exactly the work jdtls exists to do. Name plus
	 *           arity within a known subtype is precise enough in practice -
	 *           measured on PlantUML: 25/25 overrides found, 0 false positives -
	 *           and any residual imprecision costs an extra line, never a missing
	 *           one.
	 */
	public List<String> findMethodImplementations(final Symbol symbol)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Truc response = client.request("textDocument/implementation",
				positionParams(symbol.file(), symbol.line(), symbol.column(), null), 30);
		if (response.containsKey("error"))
			throw new IOException("textDocument/implementation failed: " + response.getObject("error"));

		final List<Truc> merged = new ArrayList<>(rawLocations(response.getTruc("result")));
		final Set<String> seen = new LinkedHashSet<>();
		for (final Truc location : merged)
			seen.add(locationKey(location));

		for (final Truc recovered : overridesJdtlsMisses(symbol))
			if (seen.add(locationKey(recovered)))
				merged.add(recovered);

		final List<String> formatted = new ArrayList<>();
		for (final Truc location : merged)
			formatted.add(formatLocation(location));

		return formatted;
	}

	/**
	 * The recovery pass described on findMethodImplementations(): walks the
	 * declaring type's subtypes and keeps every member declaring symbol.name() with
	 * the same arity. Best effort throughout - anything unresolvable (symbol not
	 * inside a type, no subtype, unreadable line) yields an empty list rather than
	 * an error, so the direct jdtls answer always stands on its own.
	 */
	private List<Truc> overridesJdtlsMisses(final Symbol symbol)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = symbol.file().toUri().toString();
		final String declaration = readLineSafely(uri, symbol.line());
		final int arity = arityAfterName(declaration, symbol.name());
		if (arity < 0)
			return List.of();

		final Truc declaringType = enclosingTypeNode(documentSymbols(uri), symbol.line() - 1);
		if (declaringType == null)
			return List.of();

		// Only generics make textDocument/implementation under-report, and this
		// pass costs one documentSymbol request per subtype - 363 of them on
		// TextBlock. Skip it entirely when neither the method nor its declaring
		// type is generic: there is nothing to recover, and the plain request is
		// already exhaustive.
		if (declaresTypeParameters(declaration) == false && isGenericType(declaringType) == false)
			return List.of();

		final Truc start = startOf(declaringType.getObject("selectionRange"));
		if (start == null)
			return List.of();

		final Truc response = client.request("textDocument/implementation",
				positionParams(symbol.file(), lineOf(start) + 1, characterOf(start), null), 30);
		if (response.containsKey("error"))
			return List.of();

		final List<Truc> found = new ArrayList<>();
		for (final Truc subtype : rawLocations(response.getObject("result")))
			collectDeclaredMethods(subtype, symbol.name(), arity, found);

		return found;
	}

	/**
	 * Adds to found every member of the type declared at subtype's location that
	 * declares a method named name taking arity parameters.
	 */
	private void collectDeclaredMethods(final Truc subtype, final String name, final int arity, final List<Truc> found)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = uriOf(subtype);
		final Truc range = rangeOf(subtype);
		final Truc start = startOf(range);
		if (uri == null || start == null)
			return;

		final Truc typeNode = typeNodeAt(documentSymbols(uri), lineOf(start));
		if (typeNode == null)
			return;

		for (final Object item : typeNode.getList("children")) {
			if (item instanceof Map == false)
				continue;

			final Truc member = castToMap(item);
			final Truc memberStart = startOf(member.getObject("selectionRange"));
			if (memberStart == null)
				continue;

			final String declaration = readLineSafely(uri, lineOf(memberStart) + 1);
			if (arityAfterName(declaration, name) != arity)
				continue;

			// jdtls answers "implementation" with concrete methods only, and
			// filters abstract ones out itself; a sub-interface re-declaring the
			// method abstractly is not an implementation of it. Same rule here,
			// so the recovered results stay homogeneous with the direct ones.
			if (isAbstractDeclaration(declaration))
				continue;

			final Truc location = new Truc();
			location.putString("uri", uri);
			location.putObject("range", member.getObject("selectionRange"));
			found.add(location);
		}
	}

	/**
	 * Whether declaration opens its own type parameter list - "&lt;SHAPE extends
	 * UShape&gt; void draw(...)" - as opposed to merely returning a generic type
	 * ("List&lt;String&gt; foo()"), which is why the match is anchored to the
	 * modifiers rather than looked for anywhere on the line. Only these methods
	 * need the recovery pass; a stray extra match would just cost time, never
	 * correctness.
	 */
	private static final Pattern OWN_TYPE_PARAMETERS = Pattern.compile(
			"^\\s*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|strictfp)\\s+)*<");

	/**
	 * Whether declaration declares a method without a body - "abstract" spelled
	 * out, or an interface method, which simply ends in ";" where a concrete one
	 * opens a "{".
	 */
	private boolean isAbstractDeclaration(final String declaration) {
		final String trimmed = declaration.strip();
		return trimmed.endsWith(";") || Pattern.compile("\\babstract\\b").matcher(trimmed).find();
	}

	private boolean declaresTypeParameters(final String declaration) {
		return declaration != null && OWN_TYPE_PARAMETERS.matcher(declaration).find();
	}

	/**
	 * Whether the type is generic ("Box&lt;T&gt;"), i.e. jdtls names it with its
	 * type parameters. A raw implementation of one ("class RawBox implements Box")
	 * declares its members against the erased types and goes missing in exactly the
	 * same way a generic method's erasure override does.
	 */
	private boolean isGenericType(final Truc typeNode) {
		final Object rawName = typeNode.getObject("name");
		return rawName instanceof String && ((String) rawName).indexOf('<') >= 0;
	}

	/**
	 * Number of parameters of the method named name declared on declaration, or -1
	 * if declaration doesn't declare one (name absent as a whole word, no parameter
	 * list, or a parameter list left unclosed on this line - a signature wrapped
	 * over several lines).
	 */
	private int arityAfterName(final String declaration, final String name) {
		if (declaration == null)
			return -1;

		final Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(declaration);
		if (matcher.find() == false)
			return -1;

		return arityOfParameterList(declaration, matcher.end() - 1);
	}

	/**
	 * Counts the parameters of the list opening at openIndex, ignoring commas
	 * nested inside generics, arrays or nested calls. -1 if the list never closes
	 * on this line.
	 */
	private int arityOfParameterList(final String declaration, final int openIndex) {
		int depth = 0;
		int angleDepth = 0;
		int parameters = 0;
		for (int i = openIndex; i < declaration.length(); i++) {
			final char current = declaration.charAt(i);
			if (current == '(' || current == '[') {
				depth++;
				if (depth == 1)
					parameters = 1;
			} else if (current == ')' || current == ']') {
				depth--;
				if (depth == 0)
					return declaration.substring(openIndex + 1, i).isBlank() ? 0 : parameters;
			} else if (current == '<') {
				angleDepth++;
			} else if (current == '>') {
				angleDepth--;
			} else if (current == ',' && depth == 1 && angleDepth == 0) {
				parameters++;
			}
		}
		return -1;
	}

	/** Innermost type-kind node whose whole range covers zeroBasedLine, or null. */
	private Truc enclosingTypeNode(final List<Object> nodes, final int zeroBasedLine) {
		for (final Object item : nodes) {
			if (item instanceof Map == false)
				continue;

			final Truc node = castToMap(item);
			if (coversLine(node, zeroBasedLine) == false)
				continue;

			final Truc deeper = enclosingTypeNode(node.getList("children"), zeroBasedLine);
			if (deeper != null)
				return deeper;

			if (isTypeKind(node))
				return node;
		}
		return null;
	}

	/** Type-kind node whose name token sits on zeroBasedLine, or null. */
	private Truc typeNodeAt(final List<Object> nodes, final int zeroBasedLine) {
		for (final Object item : nodes) {
			if (item instanceof Map == false)
				continue;

			final Truc node = castToMap(item);
			final Truc start = startOf(node.getObject("selectionRange"));
			if (isTypeKind(node) && start != null && lineOf(start) == zeroBasedLine)
				return node;

			final Truc deeper = typeNodeAt(node.getList("children"), zeroBasedLine);
			if (deeper != null)
				return deeper;
		}
		return null;
	}

	private boolean coversLine(final Truc node, final int zeroBasedLine) {
		final Truc range = castToMap(node.getObject("range"));
		if (range == null)
			return false;

		final Truc start = startOf(range);
		final Truc end = castToMap(range.getObject("end"));
		if (start == null || end == null)
			return false;

		return lineOf(start) <= zeroBasedLine && zeroBasedLine <= lineOf(end);
	}

	private boolean isTypeKind(final Truc node) {
		final long kindCode = node.getAsLongOrMinusOn("kind", -1);
		return TYPE_SYMBOL_KINDS.contains((int) kindCode);
	}

	private Truc startOf(final Object range) {
		final Truc asMap = castToMap(range);
		return asMap == null ? null : castToMap(asMap.getObject("start"));
	}

	private int lineOf(final Truc position) {
		return (int) position.getAsLongOrMinusOn("line", -1);
	}

	private int characterOf(final Truc position) {
		return (int) position.getAsLongOrMinusOn("character", 0);
	}

	private String uriOf(final Truc location) {
		final String uri = location.getString("uri");
		return uri != null ? uri : location.getString("targetUri");
	}

	private Truc rangeOf(final Truc location) {
		final Object range = location.getObject("range") != null ? location.getObject("range")
				: location.getObject("targetSelectionRange");
		return range instanceof Map ? castToMap(range) : null;
	}

	/** "uri:line" - identity of a location for de-duplication purposes. */
	private String locationKey(final Truc location) {
		final Truc start = startOf(rangeOf(location));
		return uriOf(location) + ":" + (start == null ? -1 : lineOf(start));
	}

	/**
	 * Same shapes formatLocations() accepts (Location, Location[], null), left raw.
	 */
	@SuppressWarnings("unchecked")
	private List<Truc> rawLocations(final Object result) {
		final List<Object> items;
		if (result instanceof List)
			items = (List<Object>) result;
		else if (result instanceof Map)
			items = List.of(result);
		else
			items = List.of();

		final List<Truc> locations = new ArrayList<>();
		for (final Object item : items)
			if (item instanceof Map)
				locations.add(castToMap(item));

		return locations;
	}

	/**
	 * textDocument/position request params, for a position already resolved by
	 * Symbol.parse().
	 */
	private Truc positionParams(final Path file, final int oneBasedLine, final int column) {
		return positionParams(file, oneBasedLine, column, null);
	}

	/**
	 * Same as the other overload, with an extra "context" entry added to the
	 * request params when non-null - see the context-taking goToPosition()
	 * overload.
	 */
	private Truc positionParams(final Path file, final int oneBasedLine, final int column, final Truc context) {
		final Truc textDocument = new Truc();
		textDocument.putString("uri", file.toUri().toString());
		final Truc position = new Truc();
		position.putLong("line", oneBasedLine - 1);
		position.putLong("character", column);
		final Truc params = new Truc();
		params.putTruc("textDocument", textDocument);
		params.putTruc("position", position);
		if (context != null)
			params.putTruc("context", context);
		return params;
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
		final Truc params = new Truc();
		params.putString("query", query);

		final Truc response = client.request("workspace/symbol", params, 30);
		if (response.containsKey("error"))
			throw new IOException("workspace/symbol failed: " + response.getObject("error"));

		return formatSymbols(response.getObject("result"));
	}

	/** Accepts either a SymbolInformation[], or null/absent. */
	@SuppressWarnings("unchecked")
	private List<String> formatSymbols(final Object result) {
		final List<Object> rawSymbols = result instanceof List ? (List<Object>) result : List.of();

		final List<String> formatted = new ArrayList<>();
		for (final Object item : rawSymbols)
			if (item instanceof Map)
				formatted.add(formatSymbol(castToMap(item)));

		return formatted;
	}

	/**
	 * "[kind] path:line: line content" - the location part reuses formatLocation()
	 * as-is: a SymbolInformation's own "location" field is a plain Location
	 * (uri+range), the same shape formatLocation() already renders for
	 * find_declaration/find_implementation.
	 */
	@SuppressWarnings("unchecked")
	private String formatSymbol(final Truc symbol) {
		final Truc location = symbol.getTruc("location");
		final String locationText = location == null ? String.valueOf(symbol.getString("name")) + ": <no location>"
				: formatLocation(location);

		return "[" + symbolKindLabel(symbol.getObject("kind")) + "] " + locationText;
	}

	/**
	 * Human label for an LSP SymbolKind code - only the kinds a Java source file
	 * can actually produce are named individually, everything else (there shouldn't
	 * be any, in practice) falls back to "symbol" rather than a bare number.
	 */
	private String symbolKindLabel(final Object kind) {
		final long code = kind instanceof Number ? ((Number) kind).longValue() : 0;
		return switch ((int) code) {
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
	 * LSP SymbolKind codes documentSymbol/workspace/symbol can return for something
	 * "class/interface/enum"-shaped - what listMembers() is willing to treat as a
	 * type to inspect. Struct(23) included even though Java has no such kind, for
	 * the same reason symbolKindLabel() names it: harmless, and one less surprise
	 * if jdtls ever reports one.
	 */
	private static final List<Integer> TYPE_SYMBOL_KINDS = List.of(5, 10, 11, 23);

	/**
	 * Best-effort cast to List<Object>, empty if value isn't one (missing/null
	 * result, wrong shape, ...).
	 */
	@SuppressWarnings("unchecked")
	private List<Object> asList(final Object value) {
		return value instanceof List ? (List<Object>) value : List.of();
	}

	/**
	 * Recursively searches a documentSymbol tree (nodes, and each node's own
	 * "children") for a type-kind node (see TYPE_SYMBOL_KINDS) named name and
	 * declared at zeroBasedLine (its own selectionRange, i.e. just the name token -
	 * matches symbol.line()-1, already whole-word-validated by Symbol.parse()).
	 * Returns the first match found (depth-first), or null.
	 */
	@SuppressWarnings("unchecked")
	private Truc findTypeNode(final List<Object> nodes, final String name, final int zeroBasedLine) {
		for (final Object item : nodes) {
			if (item instanceof Map == false)
				continue;

			final Truc node = Truc.fromMap((Map<String, Object>) item);
			if (isMatchingTypeNode(node, name, zeroBasedLine))
				return node;

			final Truc foundInChildren = findTypeNode(node.getList("children"), name, zeroBasedLine);
			if (foundInChildren != null)
				return foundInChildren;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private boolean isMatchingTypeNode(final Truc node, final String name, final int zeroBasedLine) {
		final Object kind = node.getObject("kind");
		final long kindCode = kind instanceof Number ? ((Number) kind).longValue() : -1;
		if (TYPE_SYMBOL_KINDS.contains((int) kindCode) == false)
			return false;
		// jdtls names a generic type after its source spelling, type parameters
		// included ("AbstractUGraphic<O>"), while <symbol> only ever carries the
		// bare name - Symbol.parse() matched it as a whole word on the line. An
		// equals() on the raw name therefore never matched a generic type, and
		// list_members failed on every one of them.
		if (name.equals(withoutTypeParameters(node.getObject("name"))) == false)
			return false;

		final Truc selectionRange = node.getTruc("selectionRange");
		final Truc start = selectionRange == null ? null : selectionRange.getTruc("start");
		final long line = start != null ? start.getAsLongOrMinusOn("line", -1) : -1;
		return line == zeroBasedLine;
	}

	/**
	 * "AbstractUGraphic&lt;O&gt;" -&gt; "AbstractUGraphic"; null unless rawName is
	 * a String.
	 */
	private String withoutTypeParameters(final Object rawName) {
		if (rawName instanceof String == false)
			return null;

		final String name = (String) rawName;
		final int angle = name.indexOf('<');
		return angle < 0 ? name : name.substring(0, angle);
	}

	private List<String> formatMembers(final String uri, final List<Object> children) {
		final List<String> formatted = new ArrayList<>();
		for (final Object item : children)
			if (item instanceof Map)
				formatted.add(formatMember(uri, castToMap(item)));

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
	private String formatMember(final String uri, final Truc member) {
		final Truc location = new Truc();
		location.putString("uri", uri);
		location.putObject("range", member.getObject("selectionRange"));

		return "[" + symbolKindLabel(member.getObject("kind")) + "] " + formatLocation(location);
	}

	/**
	 * Wraps one node of the parsed response. Truc.fromMap() only wraps the root:
	 * everything nested underneath is still a plain Map, so every step down the
	 * tree goes through here. null (absent key, wrong shape) stays null.
	 */
	@SuppressWarnings("unchecked")
	private Truc castToMap(final Object value) {
		if (value instanceof Truc)
			return (Truc) value;

		return value instanceof Map ? Truc.fromMap((Map<String, Object>) value) : null;
	}

	/**
	 * Renders a Hover response's "contents", which can be a plain string, a
	 * MarkupContent ({"value": "..."}), a (deprecated) MarkedString in the same
	 * {"value": "..."} shape, or an array mixing any of those - jdtls' own choice,
	 * not something clide controls, so every shape is handled rather than assumed.
	 */
	private String formatHover(final Object result) {
		if (result instanceof Map == false)
			return "<no hover info>";

		final String text = hoverText(castToMap(result).getObject("contents"));
		return text == null || text.isBlank() ? "<no hover info>" : text.strip();
	}

	@SuppressWarnings("unchecked")
	private String hoverText(final Object contents) {
		if (contents instanceof String)
			return (String) contents;

		if (contents instanceof Map) {
			final Object value = castToMap(contents).getObject("value");
			return value == null ? null : value.toString();
		}

		if (contents instanceof List) {
			final StringBuilder combined = new StringBuilder();
			for (final Object item : (List<Object>) contents) {
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
				formatted.add(formatLocation(castToMap(item)));

		return formatted;
	}

	/**
	 * Also understands LocationLink (targetUri/targetSelectionRange) in case a
	 * future capabilities change makes jdtls prefer that shape over plain Location
	 * (uri/range) - harmless either way since only one shape is ever populated.
	 */
	@SuppressWarnings("unchecked")
	private String formatLocation(final Truc location) {
		final String uri = location.getString("uri") != null ? location.getString("uri")
				: location.getString("targetUri");
		final Truc range = location.getTruc("range") != null ? location.getTruc("range")
				: location.getTruc("targetSelectionRange");

		long line = -1;
		if (range != null) {
			final Truc start = range.getTruc("start");
			if (start != null && start.getAsLongOrMinusOn("line", -1) != -1)
				line = start.getAsLongOrMinusOn("line", -1) + 1;

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

		final Map<String, List<Truc>> sorted = new TreeMap<>(diagnosticsByUri);
		int errorCount = 0;
		int warningCount = 0;
		int filesWithIssues = 0;
		for (final Map.Entry<String, List<Truc>> entry : sorted.entrySet()) {
			final List<Truc> diagnostics = entry.getValue();
			if (diagnostics.isEmpty())
				continue;

			filesWithIssues++;
			boolean headerPrinted = false;
			for (final Truc diagnostic : diagnostics) {
				final Object severity = diagnostic.getObject("severity");
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
	private String formatDiagnostic(final Truc diagnostic) {
		final Object severity = diagnostic.getObject("severity");
		final long severityCode = severity instanceof Number ? ((Number) severity).longValue() : 0;
		final String severityLabel = severityCode == 1 ? "error" : severityCode == 2 ? "warning" : "info";
		final Truc range = diagnostic.getTruc("range");
		long line = -1;
		if (range != null) {
			final Truc start = range.getTruc("start");
			if (start != null && start.getAsLongOrMinusOn("line", -1) != -1)
				line = start.getAsLongOrMinusOn("line", -1) + 1;

		}
		return "[" + severityLabel + "] line " + line + ": " + diagnostic.getString("message");
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
				client.notify("exit", new Truc());
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
				final Truc notification = client.notifications().take();
				final Object method = notification.getString("method");
				if ("textDocument/publishDiagnostics".equals(method)) {
					final Truc params = notification.getTruc("params");
					if (params != null) {
						final String uri = params.getString("uri");
						final List<Object> rawDiagnostics = (List<Object>) params.getOrDefault("diagnostics",
								List.of());
						final List<Truc> diagnostics = new ArrayList<>();
						for (final Object item : rawDiagnostics)
							diagnostics.add(Truc.fromMap((Map<String, Object>) item));

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

	private Truc initializeParams() {
		final String rootUri = projectUri();

		final Truc workspaceFolder = new Truc();
		workspaceFolder.putString("uri", rootUri);
		workspaceFolder.putString("name", projectRoot.getFileName().toString());

		final Truc gradleSettings = new Truc();
		gradleSettings.putBoolean("enabled", false);
		final Truc mavenSettings = new Truc();
		mavenSettings.putBoolean("enabled", false);
		final Truc importSettings = new Truc();
		importSettings.putTruc("gradle", gradleSettings);
		importSettings.putTruc("maven", mavenSettings);
		// Without this, workspace/symbol (and so find_symbol) only ever returns
		// types (classes/interfaces/enums/records/annotations), never methods -
		// confirmed empirically (see CLAUDE.md, "Capacites de jdtls"). Does NOT
		// cover fields: jdtls has no field search in workspace/symbol at all,
		// with or without this setting.
		final Truc symbolsSettings = new Truc();
		symbolsSettings.putBoolean("includeSourceMethodDeclarations", true);
		final Truc javaSettings = new Truc();
		javaSettings.putTruc("import", importSettings);
		javaSettings.putTruc("symbols", symbolsSettings);
		final Truc settings = new Truc();
		settings.putTruc("java", javaSettings);
		final Truc initializationOptions = new Truc();
		initializationOptions.putTruc("settings", settings);

		final Truc publishDiagnostics = new Truc();
		publishDiagnostics.putBoolean("relatedInformation", true);
		// Without this, jdtls has no signal that clide can handle the nested
		// DocumentSymbol[] shape (range/selectionRange/children) and falls back to a
		// flat SymbolInformation[] instead - which has no "children" at all, so
		// listMembers() could never find any member.
		final Truc documentSymbolCapabilities = new Truc();
		documentSymbolCapabilities.putBoolean("hierarchicalDocumentSymbolSupport", true);
		final Truc textDocumentCapabilities = new Truc();
		textDocumentCapabilities.putTruc("publishDiagnostics", publishDiagnostics);
		textDocumentCapabilities.putTruc("documentSymbol", documentSymbolCapabilities);
		final Truc capabilities = new Truc();
		capabilities.putTruc("textDocument", textDocumentCapabilities);

		final Truc params = new Truc();
		params.putNull("processId");
		params.putString("rootUri", rootUri);
		params.putList("workspaceFolders", List.of(workspaceFolder));
		params.putTruc("capabilities", capabilities);
		params.putTruc("initializationOptions", initializationOptions);
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
