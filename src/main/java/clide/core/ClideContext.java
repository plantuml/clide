package clide.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import clide.PrintMode;
import clide.jdtls.JdtlsSession;
import clide.profile.ProfileScope;
import clide.profile.Recording;

/**
 * State shared across every command execution for the lifetime of the clide
 * daemon: the project's root (what a ParamType.POSITION notation's relative
 * file path resolves against - see PositionParser.parse() - never the daemon process' own
 * current directory), the single jdtls session for this project, the list of
 * registered commands (for help), and the two distinct ways a client
 * interaction can end:
 * <ul>
 * <li>"exit"/"quit" (see DisconnectCommand) - stop the jdtls session and end
 * only the current connection. The daemon and its .clide.lock stay up; the next
 * command that actually needs jdtls restarts it lazily - see
 * ClideDaemon.ensureSessionReady().</li>
 * <li>"terminate" (see TerminateCommand) - stop the jdtls session, end the
 * connection, and shut the whole daemon down.</li>
 * </ul>
 * ClideDaemon resets isDisconnectRequested() at the start of every new
 * connection; isShutdownRequested() is one-way and checked by both the
 * per-connection loop and the daemon's own accept loop.
 *
 * getPrintMode(), unlike everything else here, is not per-connection: it is
 * set once, by ClideDaemon.run() right after this context is constructed, to
 * whatever mode the daemon itself started in (see Main), and never touched
 * again for the rest of this context's life - deliberately left out of
 * resetPerConnectionSettings(). Every connection this daemon ever serves reads
 * back the same value.
 */
public class ClideContext {

	private final Map<String, Command> commandsByKeywords = new TreeMap<>();

	/**
	 * How many entries a listing command returns unless this connection says
	 * otherwise. High enough that a normal question is answered in full, low
	 * enough that find_reference on something like PlantUML's UGraphic does not
	 * bury the answer under its own output.
	 */
	public static final int DEFAULT_MAX_RESULTS = 100;

	/**
	 * The largest value set_max_results accepts. Not a silent clamp: a request
	 * above it is refused, naming the ceiling, because a cap that quietly ignores
	 * what it was told is how a client ends up believing it disabled truncation.
	 */
	public static final int MAX_RESULTS_CEILING = 10000;

	private final FilesRepository filesRepository;
	private final JdtlsSession session;
	private final TransactionStack transactions;
	private final NamedSnapshots namedSnapshots = new NamedSnapshots();
	private boolean shutdownRequested;
	private boolean disconnectRequested;
	private PrintMode printMode = PrintMode.AI;
	private int maxResults = DEFAULT_MAX_RESULTS;
	private final Map<String, String> testEnvironment = new LinkedHashMap<>();
	private final List<String> testClasspathPrefix = new ArrayList<>();
	private final List<String> testJvmOptions = new ArrayList<>();
	private boolean profileIncludesTests;
	private Recording lastRecording = Recording.none();

	/** What set_test_env accepts as a variable name - what a shell would call one. */
	private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	public ClideContext(final FilesRepository filesRepository, final JdtlsSession session, Collection<Command> commands) {
		this.filesRepository = filesRepository;
		this.session = session;
		this.transactions = new TransactionStack(filesRepository);

		for (final Command command : commands) {
			final String keyword = command.getKeyword();
			if (keyword == null)
				throw new IllegalStateException(
						command.getClass().getName() + " has no @Keyword on its no-arg constructor");
			if (commandsByKeywords.containsKey(keyword))
				throw new IllegalStateException("Duplicate @Keyword \"" + keyword + "\": "
						+ commandsByKeywords.get(keyword).getClass().getName() + " and " + command.getClass().getName());

			commandsByKeywords.put(keyword, command);
		}

	}

	public Collection<Command> getAllCommands() {
		return commandsByKeywords.values();
	}

	public Command getCommand(String keyword) {
		return commandsByKeywords.get(keyword);
	}

	/**
	 * Root of the project this daemon owns - every relative file path in a
	 * ParamType.POSITION notation (see PositionParser.parse()) resolves against this,
	 * never against the daemon process' own current directory.
	 */
	public Path getProjectRoot() {
		return filesRepository.getProjectRoot();
	}

	public FilesRepository getFilesRepository() {
		return filesRepository;
	}

	public JdtlsSession getCurrentSession() {
		return session;
	}

	/**
	 * The snapshots clients took by name (snapshot, changed_since). Held by the
	 * daemon, so they outlive the connection that took them and are deliberately
	 * not part of resetPerConnectionSettings() - see NamedSnapshots.
	 */
	public NamedSnapshots getNamedSnapshots() {
		return namedSnapshots;
	}

	/** The stack of currently-open transactions for this project - see TransactionStack, CLAUDE.md. */
	public TransactionStack getTransactions() {
		return transactions;
	}

	/**
	 * Stops the jdtls session. Safe to call more than once, and safe to follow
	 * later with JdtlsSession.start()/build() to bring it back up - see
	 * ClideDaemon.ensureSessionReady().
	 */
	public void stopSession() {
		session.stop();
	}

	/**
	 * "exit"/"quit": end the current client connection - the clide daemon (and its
	 * jdtls session, restarted lazily on demand) stay up for the next one.
	 */
	public void requestDisconnect() {
		disconnectRequested = true;
	}

	public boolean isDisconnectRequested() {
		return disconnectRequested;
	}


	/**
	 * This daemon's print mode - AI unless it was started with --human (see
	 * Main). A command reads this when its output should differ for a human and
	 * for a machine; HelpCommand is the one that does today.
	 */
	public PrintMode getPrintMode() {
		return printMode;
	}

	/**
	 * Set once, right after this context is constructed - see ClideDaemon.run().
	 * Not called again afterward: the daemon's print mode does not change for
	 * the rest of its life.
	 */
	public void setPrintMode(final PrintMode printMode) {
		this.printMode = printMode;
	}

	/**
	 * How many entries the commands that answer with a list return at most - see
	 * Listing, and set_max_results to change it.
	 *
	 * A setting of the connection being served, not of the daemon: it goes back to
	 * DEFAULT_MAX_RESULTS at the start of every connection (see
	 * resetPerConnectionSettings()), exactly as printMode is re-read from every
	 * handshake. Inheriting a cap somebody else set in an earlier session, with no
	 * way to notice it had been set, would be a fine way to read a truncated
	 * answer as a complete one.
	 */
	public int getMaxResults() {
		return maxResults;
	}

	public void setMaxResults(final int maxResults) {
		if (maxResults < 0)
			throw new IllegalArgumentException("maxResults must not be negative: " + maxResults);

		if (maxResults > MAX_RESULTS_CEILING)
			throw new IllegalArgumentException("maxResults must not exceed " + MAX_RESULTS_CEILING);

		this.maxResults = maxResults;
	}

	/**
	 * Environment variables added to the JVM run_test and run_tests fork, on top
	 * of the daemon's own - see set_test_env. Read-only view, in the order they
	 * were set.
	 *
	 * Per connection, like maxResults and for the same reason: a variable
	 * inherited from an earlier session, with no way to notice it was set, changes
	 * what a test does (VEGA_FORCE_WRITE rewrites reference files) without anyone
	 * having asked for it.
	 */
	public Map<String, String> getTestEnvironment() {
		return Collections.unmodifiableMap(testEnvironment);
	}

	/**
	 * Adds or replaces one variable of the test JVM's environment and returns the
	 * value it had before, or null if it was not set by this connection.
	 *
	 * @throws IllegalArgumentException if name is not a plain variable name
	 */
	public String setTestEnvironment(final String name, final String value) {
		if (ENVIRONMENT_NAME.matcher(name).matches() == false)
			throw new IllegalArgumentException("'" + name
					+ "' is not a valid environment variable name - expected letters, digits and _, not starting with a digit");

		return testEnvironment.put(name, value);
	}

	/**
	 * Classpath entries put in front of the project's own for run_test and
	 * run_tests - see set_test_classpath_prefix. Read-only view.
	 *
	 * In front, not behind: the first entry that holds a class wins, so a jar
	 * listed here replaces the project's compiled classes of the same name, which
	 * is what "run these tests against that build" means. Per connection, for the
	 * reason getTestEnvironment() gives.
	 */
	public List<String> getTestClasspathPrefix() {
		return Collections.unmodifiableList(testClasspathPrefix);
	}

	/** Replaces the whole prefix, returning the previous one. Entries are not checked here. */
	public List<String> setTestClasspathPrefix(final List<String> entries) {
		final List<String> previous = List.copyOf(testClasspathPrefix);
		testClasspathPrefix.clear();
		testClasspathPrefix.addAll(entries);
		return previous;
	}

	/**
	 * Options of the JVM that run_test and run_tests fork, one per element -
	 * -Xmx512m, -XX:+UseSerialGC, -Xlog:gc... - see set_test_jvm_options. Read-only
	 * view.
	 *
	 * They land after -ea and before -cp, so a later option wins over an earlier
	 * one (-da turns -ea off) and none of them can be mistaken for an argument of
	 * the test runner. Per connection, for the reason getTestEnvironment() gives: a
	 * -Xint inherited from an earlier session would make every test crawl without
	 * anyone having asked for it.
	 */
	public List<String> getTestJvmOptions() {
		return Collections.unmodifiableList(testJvmOptions);
	}

	/**
	 * Replaces the whole list, returning the previous one. Nothing changes when an
	 * option is refused.
	 *
	 * What is refused is what the test JVM's command line already decides for
	 * itself: the classpath (-cp, -classpath, --class-path, which would silently
	 * replace the one clide assembles - set_test_classpath_prefix is the way in) and
	 * -jar (which would run something other than the tests). Anything not starting
	 * with '-' is refused too: it would end the JVM's options and be read as the
	 * main class.
	 *
	 * @throws IllegalArgumentException naming the first option that is refused
	 */
	public List<String> setTestJvmOptions(final List<String> options) {
		for (final String option : options) {
			if (option.isEmpty() || option.startsWith("-") == false)
				throw new IllegalArgumentException("'" + option
						+ "' is not a JVM option - every option starts with '-' (a bare word would be read as the main class)");

			if (option.equals("-cp") || option.equals("-classpath") || option.startsWith("--class-path"))
				throw new IllegalArgumentException("'" + option
						+ "' is refused: clide assembles the test classpath itself - use set_test_classpath_prefix to put entries in front of it");

			if (option.equals("-jar"))
				throw new IllegalArgumentException("'-jar' is refused: the test JVM runs clide's test runner, not a jar");
		}

		final List<String> previous = List.copyOf(testJvmOptions);
		testJvmOptions.clear();
		testJvmOptions.addAll(options);
		return previous;
	}

	/**
	 * Whether a profile counts the project's test code as its own - see
	 * set_profile_scope. False by default: the harness around the code under test
	 * would otherwise be the first hotspot of every profile. Per connection, for the
	 * reason getTestEnvironment() gives.
	 */
	public boolean isProfileIncludingTests() {
		return profileIncludesTests;
	}

	public void setProfileIncludingTests(final boolean includeTests) {
		profileIncludesTests = includeTests;
	}

	/** What a profile attributes to the project's own code, under the current setting. */
	public ProfileScope getProfileScope() {
		return ProfileScope.of(getProjectRoot(), profileIncludesTests);
	}

	/**
	 * The recording of the last profile_test/profile_tests - what profile_report
	 * reads. Deliberately the daemon's and not the connection's, like the named
	 * snapshots: a profile taken by one session or script is still there for the
	 * next. Recording.none() until the first profile, and files() is empty then.
	 */
	public synchronized Recording getLastRecording() {
		return lastRecording;
	}

	public synchronized void setLastRecording(final Recording recording) {
		lastRecording = recording;
	}

	/** Forgets every set_test_env, set_test_classpath_prefix, set_test_jvm_options and set_profile_scope of this connection. */
	public void resetTestSettings() {
		testEnvironment.clear();
		testClasspathPrefix.clear();
		testJvmOptions.clear();
		profileIncludesTests = false;
	}

	/**
	 * Puts back everything a connection is allowed to change for itself alone -
	 * called by ClideDaemon.serveOneClient() before a new client is served. Today:
	 * the disconnect flag an earlier exit/quit may have left set, maxResults, and
	 * the test environment, classpath prefix and JVM options.
	 */
	public void resetPerConnectionSettings() {
		disconnectRequested = false;
		maxResults = DEFAULT_MAX_RESULTS;
		resetTestSettings();
	}

	/** "terminate": end this connection and shut the whole daemon down. */
	public void requestShutdown() {
		shutdownRequested = true;
	}

	public boolean isShutdownRequested() {
		return shutdownRequested;
	}

}
