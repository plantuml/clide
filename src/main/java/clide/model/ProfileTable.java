package clide.model;

/**
 * One view of a recording: its name (hot, inclusive, alloc...), what value
 * counts (samples, bytes or ms), the total percent is taken against, and the
 * rows, biggest first, capped like any listing.
 */
public record ProfileTable(String view, String unit, long total, Listing<ProfileRow> rows) {

	public ProfileTable {
		if (view == null || view.isEmpty())
			throw new IllegalArgumentException("view must not be empty");

		if (unit == null || unit.isEmpty())
			throw new IllegalArgumentException("unit must not be empty");

		if (rows == null)
			throw new IllegalArgumentException("rows must not be null - use Listing.empty()");
	}

}
