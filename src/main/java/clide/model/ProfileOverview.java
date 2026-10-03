package clide.model;

/**
 * The headline numbers of a recording - what an agent reads first, before
 * deciding which table to open.
 *
 * cpuSamples counts every execution sample; samplesOutOfScope is how many of
 * them had no frame in the profiled code at all (harness, JUnit, JDK-only
 * stacks), so a low share of the total says the profile is mostly about
 * something else. allocatedBytes is the sampled allocation weight, an estimate
 * and not an exact count. exceptions is the number thrown during the recording.
 * contentionMillis is time blocked or parked in project frames. Everything is
 * summed over every recording of the run: a run_tests with several test output
 * folders forks one JVM, hence one recording, per folder.
 */
public record ProfileOverview(long recordingMillis, long cpuSamples, long samplesOutOfScope, long gcCount,
		long gcPausedMillis, long gcLongestMillis, long allocatedBytes, long exceptions, long contentionMillis) {

}
