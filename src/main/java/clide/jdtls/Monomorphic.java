package clide.jdtls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSON value - object, array, string, number, boolean or null - modelled by
 * a single class rather than by Object, so that no caller ever has to cast and
 * no caller can be handed something whose shape it did not ask for.
 *
 * The point is that it covers *every* JSON value, not only objects. That is
 * what Truc got wrong: Truc modelled the object, but everything nested under it
 * stayed a raw Map, so two representations of the same tree coexisted and the
 * code kept having to ask "is this one a Truc or a Map?". Here there is one
 * representation, and Json.parse() can hand back a Monomorphic rather than an
 * Object.
 *
 * Immutable. Every field is final, exactly one is meaningful for a given type,
 * and the collections handed in are copied then wrapped unmodifiable - so a
 * value parsed on LspClient's reader thread can be read from another thread
 * with no precaution. Objects are built with mapBuilder().
 *
 * Reading a value as the wrong type throws IllegalStateException naming both
 * the expected and the actual type, rather than a bare ClassCastException or -
 * worse - a null that travels a while before failing somewhere unrelated.
 */
public final class Monomorphic {

	private final MonomorphicType type;

	private final boolean bool;
	private final String string;

	/**
	 * NUMBER carries both primitives: JSON has a single number type, but jdtls
	 * does not treat 41 and 41.0 alike, and the JSON-RPC id - matched against
	 * LspClient's table of pending requests - has to survive as an exact long.
	 * integral says which of the two the value was written as; the other field
	 * holds the same number, so asLong() and asDouble() both work either way.
	 */
	private final long integer;
	private final double decimal;
	private final boolean integral;

	private final List<Monomorphic> list;
	private final Map<String, Monomorphic> map;

	private Monomorphic(final MonomorphicType type, final boolean bool, final String string, final long integer,
			final double decimal, final boolean integral, final List<Monomorphic> list,
			final Map<String, Monomorphic> map) {
		this.type = type;
		this.bool = bool;
		this.string = string;
		this.integer = integer;
		this.decimal = decimal;
		this.integral = integral;
		this.list = list;
		this.map = map;
	}

	private static final Monomorphic NULL = new Monomorphic(MonomorphicType.NULL, false, null, 0, 0, false, null, null);
	private static final Monomorphic TRUE = new Monomorphic(MonomorphicType.BOOLEAN, true, null, 0, 0, false, null,
			null);
	private static final Monomorphic FALSE = new Monomorphic(MonomorphicType.BOOLEAN, false, null, 0, 0, false, null,
			null);

	// ------------------------------------------------------------------
	// Creation
	// ------------------------------------------------------------------

	public static Monomorphic createNull() {
		return NULL;
	}

	/**
	 * A JSON string. Rejects a Java null on purpose: an absent value is
	 * createNull(), and letting null through here is how a missing field ends up
	 * travelling silently as an empty string.
	 */
	public static Monomorphic createString(final String value) {
		if (value == null)
			throw new IllegalArgumentException("null is not a STRING - use Monomorphic.createNull()");

		return new Monomorphic(MonomorphicType.STRING, false, value, 0, 0, false, null, null);
	}

	public static Monomorphic createBoolean(final boolean value) {
		return value ? TRUE : FALSE;
	}

	/** A JSON number written as an integer: 41, not 41.0. */
	public static Monomorphic createNumber(final long value) {
		return new Monomorphic(MonomorphicType.NUMBER, false, null, value, value, true, null, null);
	}

	/** A JSON number written with a fractional part or an exponent. */
	public static Monomorphic createNumber(final double value) {
		return new Monomorphic(MonomorphicType.NUMBER, false, null, (long) value, value, false, null, null);
	}

	/** Copies the list - later changes to the argument do not show up here. */
	public static Monomorphic createList(final List<Monomorphic> values) {
		if (values == null)
			throw new IllegalArgumentException("null is not a LIST - use Monomorphic.createNull()");

		final List<Monomorphic> copy = new ArrayList<>(values.size());
		for (final Monomorphic value : values) {
			if (value == null)
				throw new IllegalArgumentException(
						"null element at index " + copy.size() + " - use Monomorphic.createNull()");

			copy.add(value);
		}
		return new Monomorphic(MonomorphicType.LIST, false, null, 0, 0, false, Collections.unmodifiableList(copy), null);
	}

	public static Monomorphic createList(final Monomorphic... values) {
		return createList(List.of(values));
	}

	/**
	 * Copies the map, keeping its iteration order - JSON object key order is not
	 * meaningful, but a stable order makes the bytes on the wire reproducible
	 * and the tests readable.
	 */
	public static Monomorphic createMap(final Map<String, Monomorphic> values) {
		if (values == null)
			throw new IllegalArgumentException("null is not a MAP - use Monomorphic.createNull()");

		final Map<String, Monomorphic> copy = new LinkedHashMap<>();
		for (final Map.Entry<String, Monomorphic> entry : values.entrySet()) {
			if (entry.getKey() == null)
				throw new IllegalArgumentException("null key");

			if (entry.getValue() == null)
				throw new IllegalArgumentException(
						"null value for key '" + entry.getKey() + "' - use Monomorphic.createNull()");

			copy.put(entry.getKey(), entry.getValue());
		}
		return new Monomorphic(MonomorphicType.MAP, false, null, 0, 0, false, null, Collections.unmodifiableMap(copy));
	}

	public static Builder mapBuilder() {
		return new Builder();
	}

	// ------------------------------------------------------------------
	// What this is
	// ------------------------------------------------------------------

	public MonomorphicType getType() {
		return type;
	}

	public boolean isNull() {
		return type == MonomorphicType.NULL;
	}

	public boolean isString() {
		return type == MonomorphicType.STRING;
	}

	public boolean isBoolean() {
		return type == MonomorphicType.BOOLEAN;
	}

	public boolean isNumber() {
		return type == MonomorphicType.NUMBER;
	}

	public boolean isList() {
		return type == MonomorphicType.LIST;
	}

	public boolean isMap() {
		return type == MonomorphicType.MAP;
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	public String asString() {
		require(MonomorphicType.STRING);
		return string;
	}

	public boolean asBoolean() {
		require(MonomorphicType.BOOLEAN);
		return bool;
	}

	/** Whether this number was written as an integer rather than as a decimal. */
	public boolean isIntegral() {
		require(MonomorphicType.NUMBER);
		return integral;
	}

	/**
	 * This number as a long. A decimal is accepted when it holds a whole number
	 * a long can represent - a JSON writer is free to send 41.0 where 41 was
	 * meant - but anything with a real fractional part is an error rather than a
	 * silent truncation.
	 */
	public long asLong() {
		require(MonomorphicType.NUMBER);
		if (integral)
			return integer;

		if (Double.isNaN(decimal) || Double.isInfinite(decimal) || decimal != Math.floor(decimal))
			throw new IllegalStateException("NUMBER " + decimal + " is not a whole number");

		if (decimal < Long.MIN_VALUE || decimal > Long.MAX_VALUE)
			throw new IllegalStateException("NUMBER " + decimal + " does not fit in a long");

		return (long) decimal;
	}

	/** This number as an int - fails rather than wrapping around. */
	public int asInt() {
		final long value = asLong();
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
			throw new IllegalStateException("NUMBER " + value + " does not fit in an int");

		return (int) value;
	}

	public double asDouble() {
		require(MonomorphicType.NUMBER);
		return integral ? integer : decimal;
	}

	/** Unmodifiable. */
	public List<Monomorphic> asList() {
		require(MonomorphicType.LIST);
		return list;
	}

	/** Unmodifiable, in insertion order. */
	public Map<String, Monomorphic> asMap() {
		require(MonomorphicType.MAP);
		return map;
	}

	// ------------------------------------------------------------------
	// Navigating
	// ------------------------------------------------------------------

	/** Number of elements of a LIST, or of entries of a MAP. */
	public int size() {
		if (type == MonomorphicType.LIST)
			return list.size();

		if (type == MonomorphicType.MAP)
			return map.size();

		throw new IllegalStateException("Expected LIST or MAP but was " + type);
	}

	public boolean containsKey(final String key) {
		require(MonomorphicType.MAP);
		return map.containsKey(key);
	}

	/**
	 * The value behind key. An absent key is an error, not a null: the caller
	 * either knows the key is there, or asks containsKey()/getOrDefault() first.
	 * A JSON null actually present in the document comes back as a NULL value,
	 * so "absent" and "explicitly null" stay distinguishable.
	 */
	public Monomorphic get(final String key) {
		require(MonomorphicType.MAP);
		final Monomorphic value = map.get(key);
		if (value == null)
			throw new IllegalArgumentException("No key '" + key + "' - keys are " + map.keySet());

		return value;
	}

	public Monomorphic getOrDefault(final String key, final Monomorphic defaultValue) {
		require(MonomorphicType.MAP);
		final Monomorphic value = map.get(key);
		return value == null ? defaultValue : value;
	}

	public Monomorphic get(final int index) {
		require(MonomorphicType.LIST);
		if (index < 0 || index >= list.size())
			throw new IllegalArgumentException("No index " + index + " - LIST holds " + list.size() + " element(s)");

		return list.get(index);
	}

	private void require(final MonomorphicType expected) {
		if (type != expected)
			throw new IllegalStateException("Expected " + expected + " but was " + type);
	}

	// ------------------------------------------------------------------
	// Value semantics
	// ------------------------------------------------------------------

	/**
	 * Two values are equal when they are the same JSON. Note that a NUMBER
	 * written 1 and one written 1.0 are NOT equal: they serialize differently,
	 * and that difference is exactly what integral exists to preserve.
	 */
	@Override
	public boolean equals(final Object other) {
		if (this == other)
			return true;

		if (other instanceof Monomorphic == false)
			return false;

		final Monomorphic that = (Monomorphic) other;
		if (type != that.type)
			return false;

		return switch (type) {
		case NULL -> true;
		case BOOLEAN -> bool == that.bool;
		case STRING -> string.equals(that.string);
		case NUMBER -> integral == that.integral
				&& (integral ? integer == that.integer : Double.compare(decimal, that.decimal) == 0);
		case LIST -> list.equals(that.list);
		case MAP -> map.equals(that.map);
		};
	}

	@Override
	public int hashCode() {
		return switch (type) {
		case NULL -> 0;
		case BOOLEAN -> Boolean.hashCode(bool);
		case STRING -> string.hashCode();
		case NUMBER -> integral ? Long.hashCode(integer) : Double.hashCode(decimal);
		case LIST -> list.hashCode();
		case MAP -> map.hashCode();
		};
	}

	/**
	 * A JSON-ish rendering, meant for reading a test failure or a log line. Not
	 * a serializer - Json writes the bytes that go on the wire, and this escapes
	 * nothing.
	 */
	@Override
	public String toString() {
		final StringBuilder out = new StringBuilder();
		appendTo(out);
		return out.toString();
	}

	private void appendTo(final StringBuilder out) {
		switch (type) {
		case NULL -> out.append("null");
		case BOOLEAN -> out.append(bool);
		case STRING -> out.append('"').append(string).append('"');
		case NUMBER -> out.append(integral ? Long.toString(integer) : Double.toString(decimal));
		case LIST -> {
			out.append('[');
			boolean first = true;
			for (final Monomorphic value : list) {
				if (first == false)
					out.append(',');

				first = false;
				value.appendTo(out);
			}
			out.append(']');
		}
		case MAP -> {
			out.append('{');
			boolean first = true;
			for (final Map.Entry<String, Monomorphic> entry : map.entrySet()) {
				if (first == false)
					out.append(',');

				first = false;
				out.append('"').append(entry.getKey()).append("\":");
				entry.getValue().appendTo(out);
			}
			out.append('}');
		}
		}
	}

	// ------------------------------------------------------------------
	// Building a MAP
	// ------------------------------------------------------------------

	/**
	 * Builds a MAP value. Keeps insertion order, a repeated key replaces the
	 * previous entry, and build() may be called more than once - each call
	 * snapshots what the builder holds at that moment.
	 */
	public static final class Builder {

		private final Map<String, Monomorphic> entries = new LinkedHashMap<>();

		private Builder() {
		}

		public Builder put(final String key, final Monomorphic value) {
			if (key == null)
				throw new IllegalArgumentException("null key");

			if (value == null)
				throw new IllegalArgumentException("null value for key '" + key + "' - use putNull(key)");

			entries.put(key, value);
			return this;
		}

		public Builder putString(final String key, final String value) {
			return put(key, createString(value));
		}

		public Builder putBoolean(final String key, final boolean value) {
			return put(key, createBoolean(value));
		}

		public Builder putNumber(final String key, final long value) {
			return put(key, createNumber(value));
		}

		public Builder putNumber(final String key, final double value) {
			return put(key, createNumber(value));
		}

		public Builder putNull(final String key) {
			return put(key, createNull());
		}

		public Builder putList(final String key, final List<Monomorphic> values) {
			return put(key, createList(values));
		}

		public Monomorphic build() {
			return createMap(entries);
		}

	}

}
