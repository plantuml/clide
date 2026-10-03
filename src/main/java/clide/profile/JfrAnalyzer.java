package clide.profile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import clide.model.ProfileOverview;

/**
 * Reads JFR recordings with jdk.jfr.consumer and aggregates them into
 * ProfileData, attributed to the code of a ProfileScope.
 *
 * jdk.jfr is part of every JDK 21, which clide already requires for jdtls, so
 * this brings no dependency. It is deliberately not "jfr view hot-methods": that
 * is a flat self-time view dominated by the JDK, with no tie to the project's
 * code and no line numbers.
 *
 * A pitfall worth keeping in mind: RecordedStackTrace.getFrames() builds new
 * objects on every call, so frames cannot be compared with == - the code works
 * with indexes into one list instead.
 */
final class JfrAnalyzer {

	private final ProfileScope scope;
	private final Map<String, String> sourceByClass = new HashMap<>();
	private final ProfileData data = new ProfileData();

	private long samples;
	private long samplesOutOfScope;
	private long gcCount;
	private Duration gcPaused = Duration.ZERO;
	private Duration gcLongest = Duration.ZERO;
	private long allocated;
	private long exceptions;
	private Duration contention = Duration.ZERO;
	private long recordingMillis;

	private JfrAnalyzer(final ProfileScope scope) {
		this.scope = scope;
	}

	/** Every recording summed into one ProfileData - one per forked JVM of a run. */
	static ProfileData analyze(final List<Path> recordings, final ProfileScope scope) throws IOException {
		final JfrAnalyzer analyzer = new JfrAnalyzer(scope);
		for (final Path recording : recordings)
			analyzer.read(recording);

		analyzer.data.overview = new ProfileOverview(analyzer.recordingMillis, analyzer.samples,
				analyzer.samplesOutOfScope, analyzer.gcCount, analyzer.gcPaused.toMillis(),
				analyzer.gcLongest.toMillis(), analyzer.allocated, analyzer.exceptions,
				analyzer.contention.toMillis());
		return analyzer.data;
	}

	private void read(final Path recording) throws IOException {
		Instant first = null;
		Instant last = null;
		long minThrowables = Long.MAX_VALUE;
		long maxThrowables = Long.MIN_VALUE;

		try (RecordingFile file = new RecordingFile(recording)) {
			while (file.hasMoreEvents()) {
				final RecordedEvent event = file.readEvent();
				if (first == null || event.getStartTime().isBefore(first))
					first = event.getStartTime();

				if (last == null || event.getEndTime().isAfter(last))
					last = event.getEndTime();

				switch (event.getEventType().getName()) {
				case "jdk.ExecutionSample" -> executionSample(event.getStackTrace());
				case "jdk.ObjectAllocationSample" -> allocationSample(event);
				case "jdk.GarbageCollection" -> garbageCollection(event);
				case "jdk.ExceptionStatistics" -> {
					// A running total since the JVM started: what happened during the
					// recording is the spread between its smallest and largest reading.
					final long throwables = event.getLong("throwables");
					minThrowables = Math.min(minThrowables, throwables);
					maxThrowables = Math.max(maxThrowables, throwables);
				}
				case "jdk.JavaMonitorEnter", "jdk.ThreadPark" -> contention(event);
				default -> {
				}
				}
			}
		} catch (final RuntimeException unreadable) {
			// RecordingFile reports a truncated or foreign file as an unchecked error.
			throw new IOException("cannot read the recording " + recording.getFileName() + ": " + unreadable.getMessage(),
					unreadable);
		}

		if (first != null)
			recordingMillis += Duration.between(first, last).toMillis();

		if (maxThrowables >= minThrowables)
			exceptions += maxThrowables - minThrowables;
	}

	private void executionSample(final RecordedStackTrace stack) {
		samples++;
		final List<RecordedFrame> frames = stack == null ? List.of() : stack.getFrames();
		final int firstProject = firstProjectIndex(frames);
		if (firstProject < 0) {
			samplesOutOfScope++;
			return;
		}

		final RecordedFrame responsible = frames.get(firstProject);
		final Site method = site(responsible, false);
		final Site line = site(responsible, true);
		if (firstProject == 0)
			add(data.self, method);
		else
			add(data.jdkLeaf, new Site(line.location(), fullName(frames.get(0).getMethod())));

		add(data.attributed, method);
		add(data.lines, line);

		// Once per sample however many times a method recurses, so that "inclusive" is
		// a share of the samples and never exceeds the whole.
		final Set<Site> present = new LinkedHashSet<>();
		for (final RecordedFrame frame : frames)
			if (isProject(frame))
				present.add(site(frame, false));

		for (final Site site : present)
			add(data.inclusive, site);

		// Edges join consecutive project methods of the stack, library frames between
		// them folded away. A method that recursed into itself is not its own caller.
		final Set<ProfileData.Edge> seen = new HashSet<>();
		Site callee = null;
		for (final RecordedFrame frame : frames) {
			if (isProject(frame) == false)
				continue;

			final Site current = site(frame, false);
			if (callee != null && callee.equals(current) == false && seen.add(new ProfileData.Edge(current, callee)))
				data.edges.merge(new ProfileData.Edge(current, callee), 1L, Long::sum);

			callee = current;
		}
	}

	private void allocationSample(final RecordedEvent event) {
		final long weight = event.getLong("weight");
		allocated += weight;
		final RecordedClass type = event.getClass("objectClass");
		data.allocByType.merge(type == null ? "?" : type.getName(), weight, Long::sum);

		final List<RecordedFrame> frames = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames();
		final int project = firstProjectIndex(frames);
		if (project >= 0)
			data.allocBySite.merge(site(frames.get(project), true), weight, Long::sum);
	}

	private void garbageCollection(final RecordedEvent event) {
		gcCount++;
		final Duration pause = event.getDuration("sumOfPauses");
		gcPaused = gcPaused.plus(pause);
		if (pause.compareTo(gcLongest) > 0)
			gcLongest = pause;
	}

	private void contention(final RecordedEvent event) {
		final List<RecordedFrame> frames = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames();
		final int project = firstProjectIndex(frames);
		if (project < 0)
			return;

		final String kind = event.getEventType().getName().substring("jdk.".length());
		final Site where = site(frames.get(project), true);
		data.contention.merge(new Site(where.location(), kind + "  " + where.name()), event.getDuration().toMillis(),
				Long::sum);
		contention = contention.plus(event.getDuration());
	}

	private static void add(final Map<Site, Long> counts, final Site key) {
		counts.merge(key, 1L, Long::sum);
	}

	private int firstProjectIndex(final List<RecordedFrame> frames) {
		for (int i = 0; i < frames.size(); i++)
			if (isProject(frames.get(i)))
				return i;

		return -1;
	}

	private boolean isProject(final RecordedFrame frame) {
		return frame.isJavaFrame() && sourceOf(frame) != null;
	}

	private String sourceOf(final RecordedFrame frame) {
		final String className = frame.getMethod().getType().getName();
		final String found = sourceByClass.computeIfAbsent(className, name -> {
			final String source = scope.sourceOf(name);
			return source == null ? "" : source;
		});
		return found.isEmpty() ? null : found;
	}

	private Site site(final RecordedFrame frame, final boolean withLine) {
		final String source = sourceOf(frame);
		final String where = withLine && frame.getLineNumber() > 0 ? source + ":" + frame.getLineNumber() : source;
		return new Site(where, shortName(frame.getMethod()));
	}

	/** "Matcher2.find": the simple class name, which is what a reader recognises. */
	private static String shortName(final RecordedMethod method) {
		final String type = method.getType().getName();
		return type.substring(type.lastIndexOf('.') + 1) + "." + method.getName();
	}

	private static String fullName(final RecordedMethod method) {
		return method.getType().getName() + "." + method.getName();
	}

}
