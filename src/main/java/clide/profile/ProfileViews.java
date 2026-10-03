package clide.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import clide.model.Listing;
import clide.model.ProfileRow;
import clide.model.ProfileTable;

/**
 * The views profile_report offers, each a sort of one of ProfileData's maps.
 *
 * Every view takes a filter - a case-insensitive substring of a row's location
 * or name, "*" for none - and the cap of any listing. callers and callees are
 * the exception: their filter says which method to look around and is
 * required, since "everyone's callers" is not a question.
 */
public final class ProfileViews {

	/** The filter that keeps every row. */
	public static final String NO_FILTER = "*";

	public static final String OVERVIEW = "overview";
	public static final String HOT = "hot";
	public static final String INCLUSIVE = "inclusive";
	public static final String SELF = "self";
	public static final String LINES = "lines";
	public static final String JDK = "jdk";
	public static final String ALLOC = "alloc";
	public static final String ALLOC_TYPES = "alloc_types";
	public static final String CONTENTION = "contention";
	public static final String CALLERS = "callers";
	public static final String CALLEES = "callees";

	/** What profile_report accepts, in the order they are documented. */
	public static final List<String> NAMES = List.of(OVERVIEW, HOT, INCLUSIVE, SELF, LINES, JDK, ALLOC, ALLOC_TYPES,
			CONTENTION, CALLERS, CALLEES);

	private ProfileViews() {
	}

	/**
	 * One table of data. Percent is against the whole of what the view counts - all
	 * the samples, all the sampled allocation - not against what the filter kept,
	 * so a filtered row still says how big it is in the run.
	 *
	 * @throws IllegalArgumentException for an unknown view, or callers/callees
	 *                                  without a method to look around
	 */
	static ProfileTable table(final ProfileData data, final String view, final String filter, final int max) {
		return switch (view) {
		case HOT -> sites(view, data.attributed, filter, data.overview.cpuSamples(), max);
		case INCLUSIVE -> sites(view, data.inclusive, filter, data.overview.cpuSamples(), max);
		case SELF -> sites(view, data.self, filter, data.overview.cpuSamples(), max);
		case LINES -> sites(view, data.lines, filter, data.overview.cpuSamples(), max);
		case JDK -> sites(view, data.jdkLeaf, filter, data.overview.cpuSamples(), max);
		case ALLOC -> sites(view, data.allocBySite, filter, data.overview.allocatedBytes(), max, "bytes");
		case ALLOC_TYPES -> types(data, filter, max);
		case CONTENTION -> sites(view, data.contention, filter, data.overview.contentionMillis(), max, "ms");
		case CALLERS -> around(data, view, filter, max, true);
		case CALLEES -> around(data, view, filter, max, false);
		default -> throw new IllegalArgumentException("unknown view '" + view + "' - expected one of " + NAMES);
		};
	}

	private static ProfileTable sites(final String view, final Map<Site, Long> counts, final String filter,
			final long total, final int max) {
		return sites(view, counts, filter, total, max, "samples");
	}

	private static ProfileTable sites(final String view, final Map<Site, Long> counts, final String filter,
			final long total, final int max, final String unit) {
		final List<ProfileRow> rows = new ArrayList<>();
		for (final Map.Entry<Site, Long> entry : counts.entrySet())
			if (entry.getKey().matches(filter))
				rows.add(row(entry.getValue(), total, entry.getKey()));

		return new ProfileTable(view, unit, total, Listing.of(sorted(rows), max));
	}

	private static ProfileTable types(final ProfileData data, final String filter, final int max) {
		final long total = data.overview.allocatedBytes();
		final List<ProfileRow> rows = new ArrayList<>();
		for (final Map.Entry<String, Long> entry : data.allocByType.entrySet())
			if (filter.equals(NO_FILTER) || entry.getKey().toLowerCase(java.util.Locale.ROOT)
					.contains(filter.toLowerCase(java.util.Locale.ROOT)))
				rows.add(new ProfileRow(entry.getValue(), percent(entry.getValue(), total), "", entry.getKey()));

		return new ProfileTable(ALLOC_TYPES, "bytes", total, Listing.of(sorted(rows), max));
	}

	/**
	 * The sampled call tree one step around the methods the filter names: who calls
	 * them (callers) or whom they call (callees), library frames folded away. Each
	 * row counts the samples in which that call was on the stack, so a method that
	 * is called from two places shows both, and the two add up to its inclusive
	 * count unless it is also running at the top of the stack.
	 */
	private static ProfileTable around(final ProfileData data, final String view, final String filter, final int max,
			final boolean callers) {
		if (filter.equals(NO_FILTER))
			throw new IllegalArgumentException(view + " needs the method to look around, not \"" + NO_FILTER + "\"");

		final Map<Site, Long> merged = new java.util.HashMap<>();
		for (final Map.Entry<ProfileData.Edge, Long> entry : data.edges.entrySet()) {
			final Site pivot = callers ? entry.getKey().callee() : entry.getKey().caller();
			final Site other = callers ? entry.getKey().caller() : entry.getKey().callee();
			if (pivot.matches(filter))
				merged.merge(other, entry.getValue(), Long::sum);
		}

		final long total = data.overview.cpuSamples();
		final List<ProfileRow> rows = new ArrayList<>();
		for (final Map.Entry<Site, Long> entry : merged.entrySet())
			rows.add(row(entry.getValue(), total, entry.getKey()));

		return new ProfileTable(view, "samples", total, Listing.of(sorted(rows), max));
	}

	private static ProfileRow row(final long value, final long total, final Site site) {
		return new ProfileRow(value, percent(value, total), site.location(), site.name());
	}

	private static double percent(final long value, final long total) {
		return total == 0 ? 0 : 100.0 * value / total;
	}

	/** Biggest first; the name then the location settle a tie, so a rerun prints the same order. */
	private static List<ProfileRow> sorted(final List<ProfileRow> rows) {
		rows.sort(Comparator.comparingLong(ProfileRow::value).reversed().thenComparing(ProfileRow::name)
				.thenComparing(ProfileRow::location));
		return rows;
	}

}
