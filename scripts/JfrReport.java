import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Prototype for clide's future profile_report: reads a .jfr and prints the
 * views an agent needs, attributed to the project's own source code.
 *
 * Usage: java JfrReport.java <recording.jfr> <project-root> <src-root>... [--top N] [--scope <src-root>]...
 *
 * --scope restricts "project frame" to the given roots (e.g. src/main/java), so
 * that test-harness code is folded like a library instead of hiding the code
 * under test.
 *
 * "Project frame" = a frame whose class has a .java file under one of the
 * source roots. Everything else (JDK, JUnit, clide, libraries) is folded into
 * the nearest project frame below it on the stack.
 */
public class JfrReport {

	private final Path projectRoot;
	private final List<Path> srcRoots = new ArrayList<>();
	private final List<Path> scopeRoots = new ArrayList<>();
	private final Map<String, String> sourceOfClass = new HashMap<>();
	private int top = 15;

	public static void main(String[] args) throws Exception {
		final JfrReport report = new JfrReport(Paths.get(args[1]).toAbsolutePath());
		for (int i = 2; i < args.length; i++) {
			if (args[i].equals("--top"))
				report.top = Integer.parseInt(args[++i]);
			else if (args[i].equals("--scope"))
				report.scopeRoots.add(report.projectRoot.resolve(args[++i]));
			else
				report.srcRoots.add(report.projectRoot.resolve(args[i]));
		}
		report.run(Paths.get(args[0]));
	}

	private JfrReport(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	/** Relative source path of a class, or null when it is not project code. */
	private String sourceOf(RecordedClass clazz) {
		final String name = clazz.getName();
		return sourceOfClass.computeIfAbsent(name, n -> {
			String outer = n;
			final int dollar = outer.indexOf('$');
			if (dollar >= 0)
				outer = outer.substring(0, dollar);
			final String rel = outer.replace('.', '/') + ".java";
			if (n.contains("$$Lambda") || n.contains("/0x"))
				return ""; // hidden classes: lambdas, method handles
			for (Path root : scopeRoots.isEmpty() ? srcRoots : scopeRoots)
				if (Files.isRegularFile(root.resolve(rel)))
					return projectRoot.relativize(root.resolve(rel)).toString().replace('\\', '/');
			return "";
		}).transform(s -> s.isEmpty() ? null : s);
	}

	private static String shortMethod(RecordedMethod m) {
		String type = m.getType().getName();
		type = type.substring(type.lastIndexOf('.') + 1);
		return type + "." + m.getName();
	}

	private static String fullMethod(RecordedMethod m) {
		return m.getType().getName() + "." + m.getName();
	}

	/** file:line Class.method - the notation an agent can feed to read_lines. */
	private String position(RecordedFrame f, boolean withLine) {
		final String src = sourceOf(f.getMethod().getType());
		return src + (withLine && f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "") + "  "
				+ shortMethod(f.getMethod());
	}

	private RecordedFrame firstProjectFrame(RecordedStackTrace st) {
		final int i = firstProjectIndex(st);
		return i < 0 ? null : st.getFrames().get(i);
	}

	private int firstProjectIndex(RecordedStackTrace st) {
		if (st == null)
			return -1;
		final List<RecordedFrame> frames = st.getFrames();
		for (int i = 0; i < frames.size(); i++)
			if (frames.get(i).isJavaFrame() && sourceOf(frames.get(i).getMethod().getType()) != null)
				return i;
		return -1;
	}

	private void run(Path jfr) throws Exception {
		long samples = 0, samplesNoProject = 0;
		final Map<String, Long> selfProject = new HashMap<>(); // leaf is project code
		final Map<String, Long> attributed = new HashMap<>(); // first project frame
		final Map<String, Long> hotLines = new HashMap<>();
		final Map<String, Long> inclusive = new HashMap<>();
		final Map<String, Long> jdkLeafFromProject = new HashMap<>();
		final Map<String, Long> allocByProject = new HashMap<>();
		final Map<String, Long> allocByType = new HashMap<>();
		long allocTotal = 0;
		int gcCount = 0;
		Duration gcPause = Duration.ZERO, gcLongest = Duration.ZERO;
		final Map<String, Long> exceptions = new LinkedHashMap<>();
		final Map<String, Long> contention = new HashMap<>();
		Duration contentionTotal = Duration.ZERO;
		java.time.Instant first = null, last = null;

		try (RecordingFile rf = new RecordingFile(jfr)) {
			while (rf.hasMoreEvents()) {
				final RecordedEvent e = rf.readEvent();
				final String type = e.getEventType().getName();
				if (first == null || e.getStartTime().isBefore(first))
					first = e.getStartTime();
				if (last == null || e.getEndTime().isAfter(last))
					last = e.getEndTime();

				switch (type) {
				case "jdk.ExecutionSample": {
					samples++;
					final RecordedStackTrace st = e.getStackTrace();
					final int pi = firstProjectIndex(st);
					if (pi < 0) {
						samplesNoProject++;
						break;
					}
					final List<RecordedFrame> frames = st.getFrames();
					final RecordedFrame pf = frames.get(pi);
					final RecordedFrame leaf = frames.get(0);
					if (pi == 0)
						selfProject.merge(position(pf, false), 1L, Long::sum);
					else
						jdkLeafFromProject.merge(fullMethod(leaf.getMethod()) + "  <-  " + position(pf, true), 1L,
								Long::sum);
					attributed.merge(position(pf, false), 1L, Long::sum);
					hotLines.merge(position(pf, true), 1L, Long::sum);
					final Set<String> seen = new HashSet<>();
					for (RecordedFrame f : frames)
						if (f.isJavaFrame() && sourceOf(f.getMethod().getType()) != null) {
							final String key = position(f, false);
							if (seen.add(key))
								inclusive.merge(key, 1L, Long::sum);
						}
					break;
				}
				case "jdk.ObjectAllocationSample": {
					final long w = e.getLong("weight");
					allocTotal += w;
					final RecordedClass oc = e.getClass("objectClass");
					allocByType.merge(oc == null ? "?" : oc.getName(), w, Long::sum);
					final RecordedFrame pf = firstProjectFrame(e.getStackTrace());
					if (pf != null)
						allocByProject.merge(position(pf, true), w, Long::sum);
					break;
				}
				case "jdk.GarbageCollection": {
					gcCount++;
					final Duration p = e.getDuration("sumOfPauses");
					gcPause = gcPause.plus(p);
					if (p.compareTo(gcLongest) > 0)
						gcLongest = p;
					break;
				}
				case "jdk.ExceptionStatistics":
					exceptions.put(e.getStartTime().toString(), e.getLong("throwables"));
					break;
				case "jdk.JavaMonitorEnter":
				case "jdk.ThreadPark": {
					final RecordedFrame pf = firstProjectFrame(e.getStackTrace());
					if (pf != null) {
						contention.merge(type.substring(4) + "  " + position(pf, true), e.getDuration().toMillis(),
								Long::sum);
						contentionTotal = contentionTotal.plus(e.getDuration());
					}
					break;
				}
				default:
				}
			}
		}

		final long throwables = exceptions.isEmpty() ? 0
				: exceptions.values().stream().mapToLong(Long::longValue).max().getAsLong()
						- exceptions.values().stream().mapToLong(Long::longValue).min().getAsLong();

		System.out.println("== overview");
		System.out.printf("recording     %s (%d ms)%n", jfr.getFileName(), Duration.between(first, last).toMillis());
		System.out.printf("cpu samples   %d  (%d with no in-scope frame: harness/JUnit/JDK-only stacks)%n", samples,
				samplesNoProject);
		System.out.printf("gc            %d collections, %d ms paused in total, longest %d ms%n", gcCount,
				gcPause.toMillis(), gcLongest.toMillis());
		System.out.printf("allocation    ~%d MB sampled-weight%n", allocTotal / (1024 * 1024));
		System.out.printf("exceptions    %d thrown%n", throwables);
		System.out.printf("contention    %d ms blocked/parked in project frames%n", contentionTotal.toMillis());

		print("== cpu: attributed to project method (own code + the JDK/library code it calls)", attributed,
				samples, "%");
		print("== cpu: inclusive (project method anywhere on the stack)", inclusive, samples, "%");
		print("== cpu: self (leaf frame is project code)", selfProject, samples, "%");
		print("== cpu: hot lines (project line responsible for the sample)", hotLines, samples, "%");
		print("== cpu: JDK/library leaf  <-  project caller", jdkLeafFromProject, samples, "%");
		print("== alloc: by project site (bytes, sampled weight)", allocByProject, allocTotal, "MB");
		print("== alloc: by type", allocByType, allocTotal, "MB");
		if (contention.isEmpty() == false)
			print("== contention: ms blocked, by project site", contention, contentionTotal.toMillis(), "ms");
	}

	private void print(String title, Map<String, Long> map, long total, String unit) {
		System.out.println();
		System.out.println(title);
		map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(top)
				.forEach(en -> {
					final double pct = total == 0 ? 0 : 100.0 * en.getValue() / total;
					final String v = switch (unit) {
					case "MB" -> String.format("%7.1f MB", en.getValue() / (1024.0 * 1024.0));
					case "ms" -> String.format("%7d ms", en.getValue());
					default -> String.format("%7d", en.getValue());
					};
					System.out.printf("%s %5.1f%%  %s%n", v, pct, en.getKey());
				});
	}
}
