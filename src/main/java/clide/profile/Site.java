package clide.profile;

/**
 * A place in the profiled code: location is "path" or "path:line", name is
 * "Class.method". For a library frame location is the project line that called
 * it and name the library method - see ProfileData.
 */
public record Site(String location, String name) {

	/** Case-insensitive substring match on both parts; "*" matches everything. */
	boolean matches(final String filter) {
		if (filter.equals(ProfileViews.NO_FILTER))
			return true;

		final String wanted = filter.toLowerCase(java.util.Locale.ROOT);
		return location.toLowerCase(java.util.Locale.ROOT).contains(wanted)
				|| name.toLowerCase(java.util.Locale.ROOT).contains(wanted);
	}

}
