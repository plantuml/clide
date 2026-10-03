package clide.test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

import clide.model.TestMeasure;

/**
 * Measures what one test costs, in the JVM that runs it, and carries the result
 * over the line protocol of TestRunnerMain as five trailing fields.
 *
 * start() is called on the thread that is about to run the test and stop() on
 * that same thread once it is done: the CPU time and the allocation counter are
 * per thread, so reading them from another one would measure that other thread.
 * JUnit calls its listeners on the executing thread, which is what makes this
 * usable from a TestExecutionListener.
 *
 * Nothing here may fail a test run. A JVM that cannot give one of the readings
 * (no thread CPU time, no allocation counter) reports -1 for it - see
 * TestMeasure - and the other four still come through.
 */
final class TestMeter {

	/** How many fields a measure takes on a record: wall, cpu, allocated, gcCount, gcMillis. */
	static final int FIELDS = 5;

	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

	private TestMeter() {
	}

	/** The state of the counters when a test started. Opaque: only stop() reads it. */
	static final class Reading {

		private final long wallNanos = System.nanoTime();
		private final long cpuNanos = currentCpuNanos();
		private final long allocatedBytes = currentAllocatedBytes();
		private final long[] gc = gcTotals();

		private Reading() {
		}
	}

	static Reading start() {
		return new Reading();
	}

	/** What happened between start and now. Must run on the thread that called start(). */
	static TestMeasure stop(final Reading started) {
		final long wall = System.nanoTime() - started.wallNanos;
		final long cpu = delta(currentCpuNanos(), started.cpuNanos);
		final long allocated = delta(currentAllocatedBytes(), started.allocatedBytes);
		final long[] gc = gcTotals();
		return new TestMeasure(wall, cpu, allocated, delta(gc[0], started.gc[0]), delta(gc[1], started.gc[1]));
	}

	/** The fields to append to a PASS or FAIL record. */
	static List<String> fields(final TestMeasure measure) {
		return List.of(Long.toString(measure.wallNanos()), Long.toString(measure.cpuNanos()),
				Long.toString(measure.allocatedBytes()), Long.toString(measure.gcCount()),
				Long.toString(measure.gcMillis()));
	}

	/**
	 * The measure carried by the FIELDS fields of a record starting at from, or
	 * UNKNOWN when the record is too short or the fields are not numbers - a
	 * record written by a clide that did not measure yet is still a valid record.
	 */
	static TestMeasure parse(final List<String> record, final int from) {
		if (record.size() < from + FIELDS)
			return TestMeasure.UNKNOWN;

		try {
			return new TestMeasure(Long.parseLong(record.get(from)), Long.parseLong(record.get(from + 1)),
					Long.parseLong(record.get(from + 2)), Long.parseLong(record.get(from + 3)),
					Long.parseLong(record.get(from + 4)));
		} catch (final NumberFormatException notNumbers) {
			return TestMeasure.UNKNOWN;
		}
	}

	/** A reading that failed on either side is a failed delta, not a huge negative number. */
	private static long delta(final long end, final long start) {
		return end < 0 || start < 0 ? -1 : end - start;
	}

	private static long currentCpuNanos() {
		try {
			return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
		} catch (final RuntimeException unsupported) {
			return -1;
		}
	}

	private static long currentAllocatedBytes() {
		try {
			if (THREADS instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()
					&& sun.isThreadAllocatedMemoryEnabled())
				return sun.getCurrentThreadAllocatedBytes();

			return -1;
		} catch (final RuntimeException | LinkageError unsupported) {
			return -1;
		}
	}

	/** {collections, milliseconds} summed over every collector of the JVM; -1 per collector counts as 0. */
	private static long[] gcTotals() {
		long count = 0;
		long millis = 0;
		for (final GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, collector.getCollectionCount());
			millis += Math.max(0, collector.getCollectionTime());
		}
		return new long[] { count, millis };
	}

}
