package clide.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import clide.model.ProfileOverview;
import clide.model.ProfileTable;

/**
 * The JFR recordings of one profiled run: where the forked JVMs are told to
 * write them, and what they say once read.
 *
 * A run forks one JVM per test output folder, so a recording is a list of
 * files, one per JVM, read together as one profile. They live in
 * .clide/tmp/profiles, which clide's own .gitignore there already covers, and
 * only the last run's are kept: starting a new recording deletes the previous
 * ones, so profiling never accumulates tens of megabytes behind the project's
 * back.
 *
 * The options are the ones PROFILING.md arrived at. settings=profile alone
 * samples every 10 to 20 ms, which gives a couple of hundred samples for an
 * eight-second run and a ranking made of noise; jdk.ExecutionSample#period=1ms
 * (a per-event override, JDK 17+) gives a couple of thousand. stackdepth=256
 * keeps deep stacks - a profile of a recursive layout engine truncated at the
 * default 64 attributes everything to its middle. dumponexit writes the file
 * when TestRunnerMain calls System.exit.
 *
 * The file is named relative to the project root because the test JVM is
 * started there, which spares the option any quoting of an absolute path.
 */
public final class Recording {

	/** Where recordings go, under the project root. */
	public static final String DIRECTORY = ".clide/tmp/profiles";

	private static final Recording NONE = new Recording(null);

	private final Path projectRoot;
	private final List<Path> files = new ArrayList<>();

	private ProfileScope analyzedScope;
	private ProfileData analyzed;
	private List<Long> analyzedStamp = List.of();

	private Recording(final Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	/** A run that is not profiled: no option added, nothing recorded. */
	public static Recording none() {
		return NONE;
	}

	/** Starts a recording of a new run, forgetting the previous one's files. */
	public static Recording start(final Path projectRoot) throws IOException {
		final Path directory = projectRoot.resolve(DIRECTORY);
		if (Files.isDirectory(directory))
			try (Stream<Path> old = Files.list(directory)) {
				for (final Path file : (Iterable<Path>) old.filter(p -> p.toString().endsWith(".jfr"))::iterator)
					Files.deleteIfExists(file);
			}

		Files.createDirectories(directory);
		return new Recording(projectRoot);
	}

	/**
	 * The JVM options for the next forked JVM - empty for none() - and the file it
	 * will write, remembered. Called once per fork.
	 */
	public synchronized List<String> nextOptions() {
		if (this == NONE)
			return List.of();

		final Path file = projectRoot.resolve(DIRECTORY).resolve("profile-" + files.size() + ".jfr");
		files.add(file);
		final String relative = DIRECTORY + "/" + file.getFileName();
		return List.of("-XX:StartFlightRecording=filename=" + relative
				+ ",settings=profile,jdk.ExecutionSample#period=1ms,dumponexit=true",
				"-XX:FlightRecorderOptions=stackdepth=256");
	}

	/** The files that were actually written: a JVM killed on a timeout leaves none. */
	public synchronized List<Path> files() {
		final List<Path> written = new ArrayList<>();
		for (final Path file : files)
			if (Files.isRegularFile(file))
				written.add(file);

		return written;
	}

	/** The headline numbers of the recording, for the scope. */
	public ProfileOverview overview(final ProfileScope scope) throws IOException {
		return data(scope).overview;
	}

	/**
	 * One view of the recording. The analysis is kept for the next call - reading a
	 * recording is the expensive part, a view of it nothing - and redone only when
	 * the scope or the files changed.
	 *
	 * @throws IllegalArgumentException for an unknown view or a missing filter, see ProfileViews
	 */
	public ProfileTable table(final ProfileScope scope, final String view, final String filter, final int max)
			throws IOException {
		return ProfileViews.table(data(scope), view, filter, max);
	}

	private synchronized ProfileData data(final ProfileScope scope) throws IOException {
		final List<Path> written = files();
		if (written.isEmpty())
			throw new IOException("the recording has no file - the JVM that was to write it did not finish");

		final List<Long> stamp = new ArrayList<>();
		for (final Path file : written)
			stamp.add(Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis());

		if (analyzed == null || scope.equals(analyzedScope) == false || stamp.equals(analyzedStamp) == false) {
			analyzed = JfrAnalyzer.analyze(written.stream().sorted(Comparator.naturalOrder()).toList(), scope);
			analyzedScope = scope;
			analyzedStamp = stamp;
		}
		return analyzed;
	}

}
