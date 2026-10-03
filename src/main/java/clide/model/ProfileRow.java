package clide.model;

/**
 * One line of a profile table. value is in the table's unit and percent its
 * share of the table's total.
 *
 * location is "path:line" - relative to the project, ready for read_lines - or
 * just "path" for a whole method, and "" when there is none (a type name in an
 * allocation-by-type table). name is "Class.method", or the type or library
 * method the row is about.
 */
public record ProfileRow(long value, double percent, String location, String name) {

	public ProfileRow {
		if (location == null)
			throw new IllegalArgumentException("location must not be null - use \"\"");

		if (name == null || name.isEmpty())
			throw new IllegalArgumentException("name must not be empty");
	}

}
