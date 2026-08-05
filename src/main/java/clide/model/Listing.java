package clide.model;

import java.util.List;

/**
 * A capped view of a list of results, carrying how many there really were.
 *
 * Lives one level below CommandResult on purpose. Counting and truncation only
 * mean something for a command that answers with a list; hover answers with one
 * blob of text and open_transaction with nothing at all, and hoisting
 * totalCount/truncated into the common envelope would force those two to answer
 * a question that does not apply to them - which is exactly how a field ends up
 * holding a plausible lie. A command that has a list composes one of these; a
 * command that has none simply does not.
 *
 * <b>Cap at formatting, count at the source.</b> of() takes the *complete*
 * result list and caps it here, so totalCount is the true count and truncated()
 * is derived from it rather than guessed. Two consequences worth stating:
 * <ul>
 * <li>A run that returns exactly maxResults items is NOT truncated - there was
 * nothing more to return. Deriving truncation from "did we hit the cap" instead
 * of from the real total is the classic way to report a complete answer as
 * incomplete.</li>
 * <li>A producer must not stop early. Whatever builds the list has to walk to
 * the end before handing it over, or totalCount describes how far it walked
 * rather than how much exists.</li>
 * </ul>
 *
 * maxResults 0 is honoured literally: zero items returned, totalCount still
 * exact, truncated true as soon as anything existed. No silent clamp to 1.
 */
public record Listing<T>(List<T> items, int totalCount, int maxResults) {

	public Listing {
		if (items == null)
			throw new IllegalArgumentException("items must not be null - use Listing.empty()");

		if (totalCount < 0)
			throw new IllegalArgumentException("totalCount must not be negative: " + totalCount);

		if (maxResults < 0)
			throw new IllegalArgumentException("maxResults must not be negative: " + maxResults);

		items = List.copyOf(items);
		if (items.size() > totalCount)
			throw new IllegalArgumentException(
					"returned " + items.size() + " item(s) out of a claimed total of " + totalCount);
	}

	/**
	 * Caps all at maxResults, remembering how many there were. all must be the
	 * complete result set - see the class doc.
	 */
	public static <T> Listing<T> of(final List<T> all, final int maxResults) {
		final int total = all.size();
		final List<T> kept = total <= maxResults ? all : all.subList(0, maxResults);
		return new Listing<>(kept, total, maxResults);
	}

	public static <T> Listing<T> empty() {
		return new Listing<>(List.of(), 0, 0);
	}

	/** How many items this listing actually carries. */
	public int returnedCount() {
		return items.size();
	}

	/** Whether items were left out - measured against the real total, never against the cap. */
	public boolean truncated() {
		return totalCount > items.size();
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	/**
	 * "3 location(s)", or "50 location(s) shown out of 312, truncated - raise the
	 * limit with set_max_results" when some were left out. noun is the singular
	 * form of what is being counted ("location", "symbol", "match"), and the
	 * "(s)" spelling matches what clide has always printed.
	 */
	public String summarize(final String noun) {
		if (truncated() == false)
			return totalCount + " " + noun + "(s)";

		return returnedCount() + " " + noun + "(s) shown out of " + totalCount
				+ ", truncated - raise the limit with set_max_results";
	}

}
