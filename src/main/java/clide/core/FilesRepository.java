package clide.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FilesRepository {

	/**
	 * Directories currentSourceFiles() never walks into - no sources there, and on
	 * a project like PlantUML they hold far more files than the sources do.
	 */
	private static final List<String> SKIPPED_DIRECTORIES = List.of(".git", "bin", "build", "target", "out", "jdtls",
			"node_modules", ".gradle", ".clide");

	/**
	 * How many files are read at once - deliberately more than the number of
	 * cores. Signing a source file is reading a few kB and hashing them: a thread
	 * spends most of its time waiting on the disk, so keeping only one per core
	 * leaves both the disk and the cores idle by turns. Measured on the PlantUML
	 * checkout, going past ~8 buys nothing and going past ~16 starts costing
	 * (2 cores: 96 ms sequential, 55 ms at 4, 52 ms at 8, 61 ms at 32).
	 */
	private static final int PARALLELISM = Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors()));

	private final Path projectRoot;
	private final Md5Repository md5Repository;

	public FilesRepository(Path projectRoot, Md5Repository md5Repository) {
		this.projectRoot = projectRoot;
		this.md5Repository = md5Repository;
	}

	public Path getProjectRoot() {
		return projectRoot;
	}

	public String projectUri() {
		final String uri = getProjectRoot().toAbsolutePath().toUri().toString();
		return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
	}

	/**
	 * Every .java file under the project with its content signed (see SourceFile),
	 * skipping the directories that hold no sources but do hold thousands of files
	 * (.git, build output, the extracted jdtls itself).
	 *
	 * Two steps, because only the second one parallelizes: the walk finds the
	 * paths - Files.walk is sequential by nature, and costs ~25 ms here - then the
	 * files are read and signed on several threads at once. Measured end to end on
	 * the PlantUML checkout (3633 sources) with only 2 cores, so a wider machine
	 * gains more: 156 ms -&gt; 111 ms on a scan where every blob is already filed,
	 * and 2.9 s -&gt; 1.5 s on the very first scan of a project, the one that has
	 * all 3633 blobs to write.
	 *
	 * Order is preserved: each thread writes its own slot of the array, and the
	 * set is filled from it afterwards, in walk order. Nothing downstream depends
	 * on that order (Snapshot keys by path, Delta sorts) - but a scan that returns
	 * its files in a different order every run is a nuisance to read in a log.
	 */
	public Set<SourceFile> currentSourceFiles() throws IOException {
		final List<Path> paths = sourcePaths();
		final SourceFile[] signed = signInParallel(paths);

		final Set<SourceFile> files = new LinkedHashSet<>();
		for (final SourceFile file : signed)
			if (file != null)
				files.add(file);

		return files;
	}

	private List<Path> sourcePaths() throws IOException {
		try (Stream<Path> walk = Files.walk(projectRoot)) {
			return walk.filter(path -> path.toString().endsWith(".java")).filter(path -> isSkipped(path) == false)
					.toList();
		}
	}

	/**
	 * The pool is created for one scan and shut down right after, rather than kept
	 * around or borrowed from the common pool: it costs ~8 µs to build, next to
	 * nothing against the tens of ms of work it is given, and the daemon never
	 * ends up with threads of ours lying idle between two builds - nor with our
	 * scan competing with whatever else the common pool is running.
	 *
	 * A slot left null means that file vanished between the walk and the read -
	 * treated as absent, exactly as before.
	 */
	private SourceFile[] signInParallel(final List<Path> paths) throws IOException {
		final SourceFile[] signed = new SourceFile[paths.size()];
		final ForkJoinPool pool = new ForkJoinPool(PARALLELISM);
		try {
			pool.submit(() -> IntStream.range(0, paths.size()).parallel()
					.forEach(i -> signed[i] = signOrNull(paths.get(i)))).get();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while reading the sources of " + projectRoot, e);
		} catch (final ExecutionException e) {
			throw new IOException(e.getCause());
		} finally {
			pool.shutdown();
		}
		return signed;
	}

	private SourceFile signOrNull(final Path path) {
		try {
			return SourceFile.fromPath(md5Repository, path);
		} catch (final IOException e) {
			// vanished between the walk and the read - treat as absent
			return null;
		}
	}

	private boolean isSkipped(final Path path) {
		for (final Path segment : projectRoot.relativize(path))
			if (SKIPPED_DIRECTORIES.contains(segment.toString()))
				return true;

		return false;
	}

	/**
	 * Whether path is one currentSourceFiles() would walk into and sign - a
	 * .java file, outside every skipped directory.
	 *
	 * Reached from Transaction (see its class doc on scope): a Snapshot never
	 * records anything about a path outside this, so "absent from the opening
	 * Snapshot" alone cannot tell a file that was created after the transaction
	 * opened apart from one this transaction never had a reason to look at in
	 * the first place - both read as "no entry". Checking this first is what
	 * keeps that second case from being mistaken for the first: without it, an
	 * untouched pom.xml sitting next to the sources would look "created" the
	 * moment it is asked about, and a restore would delete a file nobody ever
	 * touched.
	 */
	boolean isSource(final Path path) {
		return path.toString().endsWith(".java") && isSkipped(path) == false;
	}

}
