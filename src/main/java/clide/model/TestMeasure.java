package clide.model;

/**
 * What one test cost, as the JVM that ran it measured it - see TestMeter, which
 * takes the readings, and TestRunnerMain, which sends them.
 *
 * All five are deltas between the start and the end of the test, fixtures
 * (@BeforeEach, @AfterEach) included:
 * <ul>
 * <li>wallNanos - elapsed wall-clock time;</li>
 * <li>cpuNanos - CPU time of the thread that ran the test;</li>
 * <li>allocatedBytes - bytes allocated by that same thread;</li>
 * <li>gcCount, gcMillis - collections and time spent in them, counted over the
 * whole JVM since the test started, so another thread's garbage can be charged
 * to this test.</li>
 * </ul>
 * cpuNanos and allocatedBytes only see the thread that ran the test: a test that
 * starts its own threads (an executor, a parallel stream) does not account for
 * their work. A value the JVM could not measure is -1, never 0, which would read
 * as "this test is free".
 */
public record TestMeasure(long wallNanos, long cpuNanos, long allocatedBytes, long gcCount, long gcMillis) {

	/** Nothing was measured - a skipped test, or a run by a clide that did not measure yet. */
	public static final TestMeasure UNKNOWN = new TestMeasure(-1, -1, -1, -1, -1);

	/**
	 * The two measures added up, as the cost of two tests run one after the other.
	 * A reading either side could not take stays unknown in the sum.
	 */
	public TestMeasure plus(final TestMeasure other) {
		return new TestMeasure(sum(wallNanos, other.wallNanos), sum(cpuNanos, other.cpuNanos),
				sum(allocatedBytes, other.allocatedBytes), sum(gcCount, other.gcCount), sum(gcMillis, other.gcMillis));
	}

	private static long sum(final long a, final long b) {
		return a < 0 || b < 0 ? -1 : a + b;
	}

	/** True when at least the wall-clock time is known - the one reading every JVM can give. */
	public boolean known() {
		return wallNanos >= 0;
	}

}
