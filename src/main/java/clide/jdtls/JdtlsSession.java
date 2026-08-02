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

	private static final List<String> CONVENTIONAL_SOURCE_FOLDERS = List.of("src/main/java", "src/main/resources",
			"src/test/java", "src/test/resources");

	/**
	 * Which of CONVENTIONAL_SOURCE_FOLDERS hold tests rather than production
	 * code. JDT only knows a source folder is a test folder if the generated
	 * .classpath says so - see buildDotClasspath().
	 */
	private static final List<String> CONVENTIONAL_TEST_FOLDERS = List.of("src/test/java", "src/test/resources");

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
	private EclipseProjectFiles eclipseFiles;
	private final Map<String, List<Monomorphic>> diagnosticsByUri = new ConcurrentHashMap<>();

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

		// Before buildDotClasspath() below (via detectJarLibs()) reads .clide/tmp/
		// jar-junit/ - see JunitVendorJars - so a project with no JUnit of its own
		// still gets one it can compile its tests against.
		JunitVendorJars.ensurePresent(projectRoot);

		eclipseFiles = EclipseProjectFiles.forProject(projectRoot);
		eclipseFiles.stage(buildDotProject(), buildDotClasspath(detectSourceFolders()));

		client = new LspClient(launcher.process().getOutputStream(), launcher.process().getInputStream());
		notificationThread = new Thread(this::processNotifications, "jdtls-notifications");
		notificationThread.setDaemon(true);
		notificationThread.start();

		final Monomorphic response = client.request("initialize", initializeParams(), 120);
		final Monomorphic error = errorOf(response);
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

	/**
	 * Source folders are guessed heuristically by checking which of the
	 * conventional Maven/Gradle layout directories actually exist on disk - used
	 * to build the .project/.classpath content that start() hands to
	 * EclipseProjectFiles.stage(), whether or not the project already had its
	 * own (see EclipseProjectFiles).
	 *
	 * Deliberately does NOT add a Gradle classpath container: without a real Gradle
	 * import (disabled - see initializeParams()), such a container never resolves
	 * anyway, so external dependencies stay unresolved either way - this at least
	 * gets the project recognized and its own source compiled.
	 */
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

	/**
	 * Test source folders are marked test="true" and given their own output
	 * folder (bin/test, production code going to the default bin/main), as
	 * "gradlew eclipse" would. Without that attribute JDT treats test code as
	 * production code, with three consequences that all bite later:
	 * java.project.isTestFile() answers false for a file that plainly is one,
	 * java.project.getClasspaths() returns the same thing for the "test" and the
	 * "runtime" scope, and every .class lands in one output folder with no way to
	 * tell tests from the rest.
	 *
	 * The jars of .clide/ stay unmarked, hence visible to production code too:
	 * nothing here can tell a test-only dependency from a real one, and guessing
	 * wrong in that direction merely fails to flag a questionable import, where
	 * guessing wrong in the other one would break a build that was fine.
	 */
	private String buildDotClasspath(final List<String> sourceFolders) {
		final StringBuilder xml = new StringBuilder();
		xml.append("""
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
				""");
		for (final String folder : sourceFolders)
			xml.append(sourceEntry(folder));

		for (final String jar : detectJarLibs())
			xml.append("\t<classpathentry kind=\"lib\" path=\"%s\"/>\n".formatted(jar));

		xml.append("""
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
					<classpathentry kind="output" path="bin/main"/>
				</classpath>
				""");
		return xml.toString();
	}

	private String sourceEntry(final String folder) {
		// No output= on production folders: they land in the project's default
		// output, declared as bin/main below. Naming a *third* folder here would
		// declare a default nothing ever writes to, and getClasspaths() reports a
		// never-created output folder as an Eclipse workspace path ("/proj/bin/
		// default") instead of a filesystem one - a bogus entry to filter out of
		// every classpath forever after.
		if (CONVENTIONAL_TEST_FOLDERS.contains(folder) == false)
			return "\t<classpathentry kind=\"src\" path=\"%s\"/>\n".formatted(folder);

		return """
				\t<classpathentry kind="src" output="bin/test" path="%s">
				\t\t<attributes>
				\t\t\t<attribute name="test" value="true"/>
				\t\t</attributes>
				\t</classpathentry>
				""".formatted(folder);
	}

	/**
	 * Jars found in <project>/.clide (flat, non-recursive) - a per-project cache
	 * populated ahead of time (e.g. with the JUnit/AssertJ/etc. jars a project's
	 * tests need), since clide's sandbox cannot reach Maven Central to resolve them
	 * itself - followed by whatever JunitVendorJars.ensurePresent() (called from
	 * start(), above) just extracted into .clide/tmp/jar-junit/: a project's own
	 * choice of JUnit wins by coming first, clide's vendored copy only fills in
	 * what a project with none of its own would otherwise be missing. Read every
	 * time start() builds a fresh .classpath to hand jdtls - see
	 * EclipseProjectFiles - so a jar dropped into .clide/ is picked up by the next
	 * daemon start without anyone having to delete an old .classpath by hand
	 * first.
	 */
	private List<String> detectJarLibs() {
		final List<String> jars = new ArrayList<>(jarsIn(projectRoot.resolve(JARS_DIR)));
		jars.addAll(jarsIn(projectRoot.resolve(JunitVendorJars.TARGET_DIR)));
		return jars;
	}

	private static List<String> jarsIn(final Path dir) {
		if (Files.isDirectory(dir) == false)
			return List.of();

		final List<String> jars = new ArrayList<>();
		try (Stream<Path> entries = Files.list(dir)) {
			entries.filter(p -> p.toString().endsWith(".jar")).sorted()
					.forEach(p -> jars.add(p.toAbsolutePath().toString().replace('\\', '/')));
		} catch (final IOException e) {
			// dir present but unreadable - classpath just ends up without these jars
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
		final Monomorphic response = client.request("java/buildWorkspace", Monomorphic.createBoolean(true), 300);
		final Monomorphic error = errorOf(response);
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
		final Map<String, Long> current = currentSourceFiles();
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
				positionParams(position.file(), position.line(), position.column(), context), 30);
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException(lspMethod + " failed: " + error);

		return formatLocations(get(response, "result"));
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
				positionParams(position.file(), position.line(), position.column()), 30);
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException("textDocument/hover failed: " + error);

		return formatHover(get(response, "result"));
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
		final Monomorphic typeNode = findTypeNode(documentSymbols(uri), position.name(), position.line() - 1);
		if (typeNode.isMap() == false)
			throw new IOException("No class/interface/enum named '" + position.name() + "' declared at line "
					+ position.line() + " of " + position.file()
					+ " (list_members only inspects types, not methods/fields)");

		return formatMembers(uri, childrenOf(typeNode));
	}

	/** Raw textDocument/documentSymbol tree for uri - empty on any error. */
	private List<Monomorphic> documentSymbols(final String uri)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic params = Monomorphic.mapBuilder()
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", uri).build()).build();

		final Monomorphic response = client.request("textDocument/documentSymbol", params, 30);
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException("textDocument/documentSymbol failed: " + error);

		return elementsOf(get(response, "result"));
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
	public List<String> findMethodImplementations(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic response = client.request("textDocument/implementation",
				positionParams(position.file(), position.line(), position.column(), null), 30);
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException("textDocument/implementation failed: " + error);

		final List<Monomorphic> merged = new ArrayList<>(rawLocations(get(response, "result")));
		final Set<String> seen = new LinkedHashSet<>();
		for (final Monomorphic location : merged)
			seen.add(locationKey(location));

		for (final Monomorphic recovered : overridesJdtlsMisses(position))
			if (seen.add(locationKey(recovered)))
				merged.add(recovered);

		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic location : merged)
			formatted.add(formatLocation(location));

		return formatted;
	}

	/**
	 * The recovery pass described on findMethodImplementations(): walks the
	 * declaring type's subtypes and keeps every member declaring position.name()
	 * with the same arity. Best effort throughout - anything unresolvable (no
	 * type enclosing position, no subtype, unreadable line) yields an empty list
	 * rather than an error, so the direct jdtls answer always stands on its own.
	 */
	private List<Monomorphic> overridesJdtlsMisses(final Position position)
			throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = position.file().toUri().toString();
		final String declaration = readLineSafely(uri, position.line());
		final int arity = arityAfterName(declaration, position.name());
		if (arity < 0)
			return List.of();

		final Monomorphic declaringType = enclosingTypeNode(documentSymbols(uri), position.line() - 1);
		if (declaringType.isMap() == false)
			return List.of();

		// Only generics make textDocument/implementation under-report, and this
		// pass costs one documentSymbol request per subtype - 363 of them on
		// TextBlock. Skip it entirely when neither the method nor its declaring
		// type is generic: there is nothing to recover, and the plain request is
		// already exhaustive.
		if (declaresTypeParameters(declaration) == false && isGenericType(declaringType) == false)
			return List.of();

		final Monomorphic start = startOf(get(declaringType, "selectionRange"));
		if (start.isMap() == false)
			return List.of();

		final Monomorphic response = client.request("textDocument/implementation",
				positionParams(position.file(), lineOf(start) + 1, characterOf(start), null), 30);
		if (errorOf(response) != null)
			return List.of();

		final List<Monomorphic> found = new ArrayList<>();
		for (final Monomorphic subtype : rawLocations(get(response, "result")))
			collectDeclaredMethods(subtype, position.name(), arity, found);

		return found;
	}

	/**
	 * Adds to found every member of the type declared at subtype's location that
	 * declares a method named name taking arity parameters.
	 */
	private void collectDeclaredMethods(final Monomorphic subtype, final String name, final int arity,
			final List<Monomorphic> found) throws IOException, InterruptedException, LspClient.TimeoutException {
		final String uri = uriOf(subtype);
		final Monomorphic start = startOf(rangeOf(subtype));
		if (uri == null || start.isMap() == false)
			return;

		final Monomorphic typeNode = typeNodeAt(documentSymbols(uri), lineOf(start));
		if (typeNode.isMap() == false)
			return;

		for (final Monomorphic member : childrenOf(typeNode)) {
			final Monomorphic memberStart = startOf(get(member, "selectionRange"));
			if (memberStart.isMap() == false)
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

			found.add(Monomorphic.mapBuilder() //
					.putString("uri", uri) //
					.put("range", get(member, "selectionRange")) //
					.build());
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
	private boolean isGenericType(final Monomorphic typeNode) {
		final String rawName = stringOrNull(get(typeNode, "name"));
		return rawName != null && rawName.indexOf('<') >= 0;
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
	private Monomorphic enclosingTypeNode(final List<Monomorphic> nodes, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false || coversLine(node, zeroBasedLine) == false)
				continue;

			final Monomorphic deeper = enclosingTypeNode(childrenOf(node), zeroBasedLine);
			if (deeper.isMap())
				return deeper;

			if (isTypeKind(node))
				return node;
		}
		return Monomorphic.createNull();
	}

	/** Type-kind node whose name token sits on zeroBasedLine, or null. */
	private Monomorphic typeNodeAt(final List<Monomorphic> nodes, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false)
				continue;

			if (isTypeKind(node) && lineOf(startOf(get(node, "selectionRange"))) == zeroBasedLine)
				return node;

			final Monomorphic deeper = typeNodeAt(childrenOf(node), zeroBasedLine);
			if (deeper.isMap())
				return deeper;
		}
		return Monomorphic.createNull();
	}

	private boolean coversLine(final Monomorphic node, final int zeroBasedLine) {
		final Monomorphic range = get(node, "range");
		final Monomorphic start = get(range, "start");
		final Monomorphic end = get(range, "end");
		if (start.isMap() == false || end.isMap() == false)
			return false;

		return lineOf(start) <= zeroBasedLine && zeroBasedLine <= lineOf(end);
	}

	private boolean isTypeKind(final Monomorphic node) {
		return TYPE_SYMBOL_KINDS.contains((int) longOrDefault(get(node, "kind"), -1));
	}

	private Monomorphic startOf(final Monomorphic range) {
		return get(range, "start");
	}

	private int lineOf(final Monomorphic position) {
		return (int) longOrDefault(get(position, "line"), -1);
	}

	private int characterOf(final Monomorphic position) {
		return (int) longOrDefault(get(position, "character"), 0);
	}

	private String uriOf(final Monomorphic location) {
		final String uri = stringOrNull(get(location, "uri"));
		return uri != null ? uri : stringOrNull(get(location, "targetUri"));
	}

	private Monomorphic rangeOf(final Monomorphic location) {
		final Monomorphic range = get(location, "range");
		return range.isMap() ? range : get(location, "targetSelectionRange");
	}

	/** "uri:line" - identity of a location for de-duplication purposes. */
	private String locationKey(final Monomorphic location) {
		return uriOf(location) + ":" + lineOf(startOf(rangeOf(location)));
	}

	/**
	 * Same shapes formatLocations() accepts (Location, Location[], null), left raw.
	 */
	private List<Monomorphic> rawLocations(final Monomorphic result) {
		if (result.isMap())
			return List.of(result);

		final List<Monomorphic> locations = new ArrayList<>();
		for (final Monomorphic item : elementsOf(result))
			if (item.isMap())
				locations.add(item);

		return locations;
	}

	/**
	 * textDocument/position request params, for a position already resolved by
	 * Position.parse().
	 */
	private Monomorphic positionParams(final Path file, final int oneBasedLine, final int column) {
		return positionParams(file, oneBasedLine, column, null);
	}

	/**
	 * Same as the other overload, with an extra "context" entry added to the
	 * request params when non-null - see the context-taking goToPosition()
	 * overload.
	 */
	private Monomorphic positionParams(final Path file, final int oneBasedLine, final int column,
			final Monomorphic context) {
		final Monomorphic.Builder params = Monomorphic.mapBuilder() //
				.put("textDocument", Monomorphic.mapBuilder().putString("uri", file.toUri().toString()).build()) //
				.put("position", Monomorphic.mapBuilder() //
						.putNumber("line", oneBasedLine - 1) //
						.putNumber("character", column) //
						.build());
		if (context != null)
			params.put("context", context);

		return params.build();
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
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException("workspace/symbol failed: " + error);

		return formatSymbols(get(response, "result"));
	}

	/** Accepts either a SymbolInformation[], or null/absent. */
	private List<String> formatSymbols(final Monomorphic result) {
		final List<String> formatted = new ArrayList<>();
		for (final Monomorphic item : elementsOf(result))
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
		final Monomorphic location = get(symbol, "location");
		final String locationText = location.isMap() == false
				? String.valueOf(stringOrNull(get(symbol, "name"))) + ": <no location>"
				: formatLocation(location);

		return "[" + symbolKindLabel(get(symbol, "kind")) + "] " + locationText;
	}

	/**
	 * Human label for an LSP SymbolKind code - only the kinds a Java source file
	 * can actually produce are named individually, everything else (there shouldn't
	 * be any, in practice) falls back to "symbol" rather than a bare number.
	 */
	private String symbolKindLabel(final Monomorphic kind) {
		return switch ((int) longOrDefault(kind, 0)) {
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

	// ------------------------------------------------------------------
	// Reading a jdtls response
	// ------------------------------------------------------------------

	/**
	 * The value behind key, or a NULL value when there is nothing there - key
	 * absent, or node not even a MAP. Every step down a response goes through
	 * here rather than through getFromMap(): an optional field jdtls chose not to
	 * send, or a shape this code did not expect, is not an error worth an
	 * exception - it means "nothing to report", which is exactly what NULL says.
	 */
	private static Monomorphic get(final Monomorphic node, final String key) {
		if (node.isMap() == false)
			return Monomorphic.createNull();

		return node.getFromMapOrDefault(key, Monomorphic.createNull());
	}

	/** The elements of a LIST, and nothing at all for any other shape. */
	private static List<Monomorphic> elementsOf(final Monomorphic value) {
		return value.isList() ? value.asList() : List.of();
	}

	/** The "children" of a documentSymbol node, empty when it has none. */
	private static List<Monomorphic> childrenOf(final Monomorphic node) {
		return elementsOf(get(node, "children"));
	}

	/** null unless the value really is a STRING - never an exception. */
	private static String stringOrNull(final Monomorphic value) {
		return value.isString() ? value.asString() : null;
	}

	private static long longOrDefault(final Monomorphic value, final long defaultValue) {
		return value.isNumber() ? value.asLong() : defaultValue;
	}

	/**
	 * The "error" member of a JSON-RPC response, or null when the call
	 * succeeded - the one place a Java null still means "absent", because every
	 * caller reads it as "throw or carry on" rather than as a value.
	 */
	private static Monomorphic errorOf(final Monomorphic response) {
		final Monomorphic error = get(response, "error");
		return error.isNull() ? null : error;
	}

	/**
	 * Recursively searches a documentSymbol tree (nodes, and each node's own
	 * "children") for a type-kind node (see TYPE_SYMBOL_KINDS) named name and
	 * declared at zeroBasedLine (its own selectionRange, i.e. just the name token -
	 * matches position.line()-1, already whole-word-validated by Position.parse()).
	 * Returns the first match found (depth-first), or null.
	 */
	private Monomorphic findTypeNode(final List<Monomorphic> nodes, final String name, final int zeroBasedLine) {
		for (final Monomorphic node : nodes) {
			if (node.isMap() == false)
				continue;

			if (isMatchingTypeNode(node, name, zeroBasedLine))
				return node;

			final Monomorphic foundInChildren = findTypeNode(childrenOf(node), name, zeroBasedLine);
			if (foundInChildren.isMap())
				return foundInChildren;
		}
		return Monomorphic.createNull();
	}

	private boolean isMatchingTypeNode(final Monomorphic node, final String name, final int zeroBasedLine) {
		if (isTypeKind(node) == false)
			return false;
		// jdtls names a generic type after its source spelling, type parameters
		// included ("AbstractUGraphic<O>"), while <position> only ever carries the
		// bare name - Position.parse() matched it as a whole word on the line. An
		// equals() on the raw name therefore never matched a generic type, and
		// list_members failed on every one of them.
		if (name.equals(withoutTypeParameters(get(node, "name"))) == false)
			return false;

		return lineOf(startOf(get(node, "selectionRange"))) == zeroBasedLine;
	}

	/**
	 * "AbstractUGraphic&lt;O&gt;" -&gt; "AbstractUGraphic"; null unless rawName is
	 * a String.
	 */
	private String withoutTypeParameters(final Monomorphic rawName) {
		final String name = stringOrNull(rawName);
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
				.put("range", get(member, "selectionRange")) //
				.build();

		return "[" + symbolKindLabel(get(member, "kind")) + "] " + formatLocation(location);
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

		final String text = hoverText(get(result, "contents"));
		return text == null || text.isBlank() ? "<no hover info>" : text.strip();
	}

	private String hoverText(final Monomorphic contents) {
		if (contents.isString())
			return contents.asString();

		if (contents.isMap()) {
			final Monomorphic value = get(contents, "value");
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
		for (final Monomorphic location : rawLocations(result))
			formatted.add(formatLocation(location));

		return formatted;
	}

	/**
	 * Also understands LocationLink (targetUri/targetSelectionRange) in case a
	 * future capabilities change makes jdtls prefer that shape over plain Location
	 * (uri/range) - harmless either way since only one shape is ever populated.
	 */
	private String formatLocation(final Monomorphic location) {
		final String uri = uriOf(location);
		final int zeroBasedLine = lineOf(startOf(rangeOf(location)));
		final long line = zeroBasedLine == -1 ? -1 : zeroBasedLine + 1;

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
				final long severityCode = longOrDefault(get(diagnostic, "severity"), 0);
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
		final long severityCode = longOrDefault(get(diagnostic, "severity"), 0);
		final String severityLabel = severityCode == 1 ? "error" : severityCode == 2 ? "warning" : "info";
		final int zeroBasedLine = lineOf(startOf(get(diagnostic, "range")));
		final long line = zeroBasedLine == -1 ? -1 : zeroBasedLine + 1;
		return "[" + severityLabel + "] line " + line + ": " + stringOrNull(get(diagnostic, "message"));
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
				if ("textDocument/publishDiagnostics".equals(stringOrNull(get(notification, "method"))) == false)
					continue;

				final Monomorphic params = get(notification, "params");
				final String uri = stringOrNull(get(params, "uri"));
				if (uri == null)
					continue;

				diagnosticsByUri.put(uri, new ArrayList<>(elementsOf(get(params, "diagnostics"))));
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
		final String rootUri = projectUri();

		final Monomorphic workspaceFolder = Monomorphic.mapBuilder() //
				.putString("uri", rootUri) //
				.putString("name", projectRoot.getFileName().toString()) //
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
		final Monomorphic error = errorOf(response);
		if (error != null)
			throw new IOException(command + " failed: " + error);

		return get(response, "result");
	}

	/**
	 * The classpath to run this project's tests on, as jdtls knows it: the output
	 * folders plus every jar of .clide/. Entries that do not exist on disk are
	 * dropped - jdtls reports an output folder nothing was ever written to as an
	 * Eclipse workspace path rather than a filesystem one.
	 *
	 * The "test" scope only differs from "runtime" when the test source folders
	 * are marked as such in .classpath - see buildDotClasspath().
	 */
	public List<String> testClasspath() throws IOException, InterruptedException, LspClient.TimeoutException {
		final Monomorphic result = executeWorkspaceCommand("java.project.getClasspaths",
				List.of(Monomorphic.createString(projectUri()), Monomorphic.createString("{\"scope\":\"test\"}")), 60);
		if (result.isMap() == false)
			throw new IOException("java.project.getClasspaths returned no classpath: " + result);

		final List<String> entries = new ArrayList<>();
		for (final Monomorphic entry : elementsOf(get(result, "classpaths"))) {
			final String path = stringOrNull(entry);
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
		for (final Monomorphic uri : elementsOf(result))
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
			return stringOrNull(result);
		} catch (final Exception unresolvable) {
			return null;
		}
	}

	private String projectUri() {
		final String uri = projectRoot.toAbsolutePath().toUri().toString();
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

}
