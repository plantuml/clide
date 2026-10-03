package clide.profile;

import java.util.HashMap;
import java.util.Map;

import clide.model.ProfileOverview;

/**
 * Everything a recording says, aggregated once so that every view is a sort of
 * a map and nothing re-reads the file.
 *
 * "Project frame" is a frame whose class is in the ProfileScope; everything
 * else is folded into the nearest project frame below it on the stack, which is
 * what makes a hotspot an answer ("this line of mine, with whatever the JDK
 * does for it") rather than a list of JDK internals. The CPU maps count samples.
 * <ul>
 * <li>attributed - the first project method on the stack, whatever is above it;</li>
 * <li>inclusive - every project method present anywhere on the stack, once per sample;</li>
 * <li>self - samples whose leaf frame is project code itself;</li>
 * <li>lines - the first project frame, down to its line;</li>
 * <li>jdkLeaf - a library method at the top of the stack, with the project line that called it;</li>
 * <li>edges - project-to-project calls as the samples saw them, once per sample.</li>
 * </ul>
 * The others are bytes of sampled allocation weight (allocation, by site and by
 * type) and milliseconds blocked (contention).
 */
final class ProfileData {

	/** A project method calling another, in the samples. */
	record Edge(Site caller, Site callee) {
	}

	ProfileOverview overview;
	final Map<Site, Long> attributed = new HashMap<>();
	final Map<Site, Long> inclusive = new HashMap<>();
	final Map<Site, Long> self = new HashMap<>();
	final Map<Site, Long> lines = new HashMap<>();
	final Map<Site, Long> jdkLeaf = new HashMap<>();
	final Map<Site, Long> allocBySite = new HashMap<>();
	final Map<String, Long> allocByType = new HashMap<>();
	final Map<Site, Long> contention = new HashMap<>();
	final Map<Edge, Long> edges = new HashMap<>();

}
