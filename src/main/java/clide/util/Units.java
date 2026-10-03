package clide.util;

import java.util.Locale;

/** How a duration and a size are written everywhere clide prints a measure. */
public final class Units {

	private Units() {
	}

	/** "0.4 ms", "12 ms", "3.2 s": the unit that keeps two significant figures at least. */
	public static String duration(final long nanos) {
		final double millis = nanos / 1_000_000.0;
		if (millis >= 10_000)
			return String.format(Locale.ROOT, "%.1f s", millis / 1000);

		if (millis >= 10)
			return Math.round(millis) + " ms";

		return String.format(Locale.ROOT, "%.1f ms", millis);
	}

	/** "812 B", "3.2 KB", "41.0 MB": binary multiples, one decimal. */
	public static String size(final long bytes) {
		if (bytes < 1024)
			return bytes + " B";

		final String[] units = { "KB", "MB", "GB", "TB" };
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

}
