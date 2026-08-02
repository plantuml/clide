package clide.jdtls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer, just enough to speak JSON-RPC with jdtls. Not a
 * general-purpose library: no streaming, no custom types, no comments.
 *
 * Unlike Json, this one never traffics in Object: it reads and writes
 * Monomorphic, so a caller of parse() gets a value it can interrogate without a
 * cast, and write() cannot be handed something it does not know how to
 * serialize - that is now a compile error rather than an
 * IllegalArgumentException discovered at runtime.
 *
 * parse() never returns null. A JSON null in the document comes back as
 * Monomorphic.createNull(), so "the document said null" and "something went
 * wrong" stop looking alike.
 *
 * Every malformed input raises IllegalArgumentException naming the character
 * position, including the ones the Object-based reader let through as a
 * StringIndexOutOfBoundsException (input ending mid-string, mid-array or
 * mid-object) and the ones it silently accepted (leading zeroes, a lone minus
 * sign, a raw control character inside a string). Both directions are
 * recursive, so both cap nesting rather than leave it to blow the stack: a
 * hostile or broken peer should get an exception a caller can catch, not a
 * StackOverflowError.
 */
public final class Json2 {

	/**
	 * How many nested objects/arrays parse() accepts, and write() produces.
	 * JSON-RPC traffic with jdtls sits around ten levels; anything near this
	 * bound is a bug or an attack, and either way it is better refused than run
	 * into the stack limit.
	 *
	 * The same bound on both sides is what makes anything parse() returns safe
	 * to hand straight back to write().
	 */
	private static final int MAX_DEPTH = 200;

	/**
	 * How much of an offending token an error message repeats. The input comes
	 * from a peer, the message usually ends up in a log, and a megabyte-long
	 * number should not become a megabyte-long log line.
	 */
	private static final int MAX_QUOTED_LENGTH = 32;

	private Json2() {
	}

	// ------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------

	/** The bytes that go on the wire, for any JSON value - not only an object. */
	public static String write(final Monomorphic value) {
		if (value == null)
			throw new IllegalArgumentException("null is not a JSON value - use Monomorphic.createNull()");

		final StringBuilder out = new StringBuilder();
		writeValue(value, out, 0);
		return out.toString();
	}

	private static void writeValue(final Monomorphic value, final StringBuilder out, final int depth) {
		switch (value.getType()) {
		case NULL -> out.append("null");
		case BOOLEAN -> out.append(value.asBoolean());
		case STRING -> writeString(value.asString(), out);
		case INTEGER -> out.append(value.asLong());
		case DECIMAL -> writeDecimal(value.asDouble(), out);
		case LIST -> writeArray(value.asList(), out, depth);
		case MAP -> writeObject(value.asMap(), out, depth);
		}
	}

	/**
	 * Nothing parse() produces can reach this - it caps at the same depth - but a
	 * value assembled in memory can, and a StackOverflowError thrown halfway
	 * through a StringBuilder is not something a caller can do anything with.
	 */
	private static void requireDepth(final int depth) {
		if (depth >= MAX_DEPTH)
			throw new IllegalArgumentException("Cannot serialize a value nested deeper than " + MAX_DEPTH);
	}

	/**
	 * JSON has no spelling for NaN or an infinity, and inventing one - bare NaN,
	 * or a quoted "Infinity" - would produce something the peer either rejects or,
	 * worse, reads as a string. Refused here instead.
	 */
	private static void writeDecimal(final double value, final StringBuilder out) {
		if (Double.isNaN(value) || Double.isInfinite(value))
			throw new IllegalArgumentException("Cannot serialize " + value + " - JSON has no such number");

		out.append(value);
	}

	private static void writeObject(final Map<String, Monomorphic> map, final StringBuilder out, final int depth) {
		requireDepth(depth);
		out.append('{');
		boolean first = true;
		for (final Map.Entry<String, Monomorphic> entry : map.entrySet()) {
			if (first == false)
				out.append(',');

			first = false;
			writeString(entry.getKey(), out);
			out.append(':');
			writeValue(entry.getValue(), out, depth + 1);
		}
		out.append('}');
	}

	private static void writeArray(final List<Monomorphic> list, final StringBuilder out, final int depth) {
		requireDepth(depth);
		out.append('[');
		boolean first = true;
		for (final Monomorphic item : list) {
			if (first == false)
				out.append(',');

			first = false;
			writeValue(item, out, depth + 1);
		}
		out.append(']');
	}

	private static void writeString(final String s, final StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			case '\b':
				out.append("\\b");
				break;
			case '\f':
				out.append("\\f");
				break;
			default:
				if (c < 0x20 || isUnpairedSurrogate(s, i))
					out.append(String.format("\\u%04x", (int) c));
				else
					out.append(c);
			}
		}
		out.append('"');
	}

	/**
	 * A surrogate that is not half of a well-formed pair. Left raw it would have
	 * no UTF-8 encoding at all: the bytes LspClient puts on the wire come from
	 * String.getBytes(UTF_8), which turns a lone surrogate into '?' - the value
	 * would arrive silently corrupted. Written as a \\u escape it survives the
	 * trip, and whoever reads it gets back exactly what was meant.
	 *
	 * A correctly paired surrogate is *not* escaped: the pair encodes to UTF-8
	 * perfectly well, and escaping it would turn every emoji into twelve bytes of
	 * unreadable JSON.
	 */
	private static boolean isUnpairedSurrogate(final String s, final int i) {
		final char c = s.charAt(i);
		if (Character.isHighSurrogate(c))
			return i + 1 >= s.length() || Character.isLowSurrogate(s.charAt(i + 1)) == false;

		if (Character.isLowSurrogate(c))
			return i == 0 || Character.isHighSurrogate(s.charAt(i - 1)) == false;

		return false;
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	/** Never null: a JSON null comes back as a NULL Monomorphic. */
	public static Monomorphic parse(final String text) {
		if (text == null)
			throw new IllegalArgumentException("Cannot parse a null string");

		final Parser parser = new Parser(text);
		final Monomorphic value = parser.parseValue(0);
		parser.skipWhitespace();
		if (parser.hasMore())
			throw new IllegalArgumentException("Unexpected trailing content in JSON at position " + parser.pos);

		return value;
	}

	private static final class Parser {

		private final String text;
		private int pos;

		private Parser(final String text) {
			this.text = text;
		}

		private boolean hasMore() {
			return pos < text.length();
		}

		/** The current character, or an exception rather than an index error. */
		private char peek() {
			if (hasMore() == false)
				throw new IllegalArgumentException("Unexpected end of JSON at position " + pos);

			return text.charAt(pos);
		}

		private char next() {
			final char c = peek();
			pos++;
			return c;
		}

		private void skipWhitespace() {
			while (hasMore() && isWhitespace(text.charAt(pos)))
				pos++;
		}

		/**
		 * The four characters RFC 8259 calls whitespace, and only those -
		 * Character.isWhitespace() would also swallow a vertical tab, a form feed
		 * and the file/group/record separators, quietly accepting documents no
		 * other parser reads.
		 */
		private static boolean isWhitespace(final char c) {
			return c == ' ' || c == '\t' || c == '\n' || c == '\r';
		}

		private static boolean isDigit(final char c) {
			return c >= '0' && c <= '9';
		}

		private Monomorphic parseValue(final int depth) {
			skipWhitespace();
			final char c = peek();
			if (c == '{')
				return parseObject(depth);

			if (c == '[')
				return parseArray(depth);

			if (c == '"')
				return Monomorphic.createString(parseString());

			if (c == 't') {
				expectLiteral("true");
				return Monomorphic.createBoolean(true);
			}
			if (c == 'f') {
				expectLiteral("false");
				return Monomorphic.createBoolean(false);
			}
			if (c == 'n') {
				expectLiteral("null");
				return Monomorphic.createNull();
			}
			if (c == '-' || isDigit(c))
				return parseNumber();

			throw new IllegalArgumentException("Unexpected character " + describe(c) + " at position " + pos);
		}

		/** A character as it should appear in an error message, in quotes. */
		private static String describe(final char c) {
			return "'" + render(c) + "'";
		}

		/**
		 * A character as it should appear in an error message. The offending input
		 * comes from a peer and the message goes to a log: a NUL or a newline
		 * recopied verbatim mangles the line, and a lone surrogate recopied
		 * verbatim gives a message that cannot even be encoded in UTF-8 - the very
		 * defect writeString() escapes surrogates to avoid.
		 */
		private static String render(final char c) {
			if (c < 0x20 || c == 0x7f || Character.isSurrogate(c))
				return String.format("\\u%04x", (int) c);

			return String.valueOf(c);
		}

		/** Every character of an excerpt rendered, and the whole kept short. */
		private static String render(final String excerpt) {
			final StringBuilder out = new StringBuilder();
			for (int i = 0; i < excerpt.length(); i++)
				out.append(render(excerpt.charAt(i)));

			return abbreviate(out.toString());
		}

		/** Keeps an offending token short enough to log - see MAX_QUOTED_LENGTH. */
		private static String abbreviate(final String token) {
			if (token.length() <= MAX_QUOTED_LENGTH)
				return token;

			return token.substring(0, MAX_QUOTED_LENGTH) + "... (" + token.length() + " characters)";
		}

		private void expectLiteral(final String literal) {
			if (text.regionMatches(pos, literal, 0, literal.length()) == false)
				throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);

			pos += literal.length();
		}

		/**
		 * depth is how many containers already enclose this one, so the check is
		 * the same for an object and for an array and MAX_DEPTH means exactly what
		 * it says - MAX_DEPTH containers accepted, the next one refused.
		 */
		private void requireDepth(final int depth) {
			if (depth >= MAX_DEPTH)
				throw new IllegalArgumentException(
						"JSON nested deeper than " + MAX_DEPTH + " at position " + pos);
		}

		private Monomorphic parseObject(final int depth) {
			requireDepth(depth);
			final Map<String, Monomorphic> map = new LinkedHashMap<>();
			pos++; // '{'
			skipWhitespace();
			if (peek() == '}') {
				pos++;
				return Monomorphic.createMap(map);
			}
			while (true) {
				skipWhitespace();
				final String key = parseString();
				skipWhitespace();
				if (peek() != ':')
					throw new IllegalArgumentException("Expected ':' at position " + pos);

				pos++;
				final Monomorphic value = parseValue(depth + 1);
				map.put(key, value);
				skipWhitespace();
				if (peek() == ',') {
					pos++;
					continue;
				}
				if (peek() == '}') {
					pos++;
					break;
				}
				throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
			}
			return Monomorphic.createMap(map);
		}

		private Monomorphic parseArray(final int depth) {
			requireDepth(depth);
			final List<Monomorphic> list = new ArrayList<>();
			pos++; // '['
			skipWhitespace();
			if (peek() == ']') {
				pos++;
				return Monomorphic.createList(list);
			}
			while (true) {
				list.add(parseValue(depth + 1));
				skipWhitespace();
				if (peek() == ',') {
					pos++;
					continue;
				}
				if (peek() == ']') {
					pos++;
					break;
				}
				throw new IllegalArgumentException("Expected ',' or ']' at position " + pos);
			}
			return Monomorphic.createList(list);
		}

		private String parseString() {
			if (peek() != '"')
				throw new IllegalArgumentException("Expected '\"' at position " + pos);

			pos++;
			final StringBuilder out = new StringBuilder();
			while (true) {
				final int at = pos;
				final char c = next();
				if (c == '"')
					break;

				if (c == '\\') {
					parseEscape(out);
					continue;
				}
				// A raw control character has to be escaped inside a JSON string.
				// Letting one through means a stray newline in a payload reads as a
				// perfectly good string here and is rejected by whoever gets it next.
				if (c < 0x20)
					throw new IllegalArgumentException(
							String.format("Unescaped control character \\u%04x at position %d", (int) c, at));

				out.append(c);
			}
			return out.toString();
		}

		private void parseEscape(final StringBuilder out) {
			final int at = pos - 1;
			final char escaped = next();
			switch (escaped) {
			case '"':
				out.append('"');
				break;
			case '\\':
				out.append('\\');
				break;
			case '/':
				out.append('/');
				break;
			case 'n':
				out.append('\n');
				break;
			case 'r':
				out.append('\r');
				break;
			case 't':
				out.append('\t');
				break;
			case 'b':
				out.append('\b');
				break;
			case 'f':
				out.append('\f');
				break;
			case 'u':
				out.append(parseUnicodeEscape());
				break;
			default:
				throw new IllegalArgumentException(
						"Unknown escape sequence '\\" + render(escaped) + "' at position " + at);
			}
		}

		/**
		 * The four hex digits of a \\u escape. Read one by one rather than through
		 * Integer.parseInt(), which would also accept "+12f", " 12f" and Arabic-Indic
		 * digits - none of which is a JSON escape.
		 */
		private char parseUnicodeEscape() {
			final int start = pos;
			int value = 0;
			for (int i = 0; i < 4; i++) {
				final char c = next();
				if (isAsciiHex(c) == false)
					throw new IllegalArgumentException("Invalid \\u escape '"
							+ render(text.substring(start, Math.min(start + 4, text.length()))) + "' at position "
							+ start);

				value = value * 16 + Character.digit(c, 16);
			}
			return (char) value;
		}

		private static boolean isAsciiHex(final char c) {
			return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
		}

		/**
		 * A number, held as an INTEGER when it was written as one - 41 has to come
		 * back out as 41 and not as 41.0, both because the JSON-RPC id is matched
		 * against what was sent and because parse-then-write is meant to be the
		 * identity.
		 */
		private Monomorphic parseNumber() {
			final int start = pos;
			if (peek() == '-')
				pos++;

			parseIntegerPart();
			boolean isDecimal = false;
			if (hasMore() && peek() == '.') {
				isDecimal = true;
				pos++;
				parseDigits("a digit after '.'");
			}
			if (hasMore() && (peek() == 'e' || peek() == 'E')) {
				isDecimal = true;
				pos++;
				if (hasMore() && (peek() == '+' || peek() == '-'))
					pos++;

				parseDigits("a digit in the exponent");
			}
			final String number = text.substring(start, pos);
			if (isDecimal == false)
				try {
					return Monomorphic.createNumber(Long.parseLong(number));
				} catch (final NumberFormatException e) {
					// Too big for a long: it becomes a double rather than an error,
					// at the cost of the exact value - the alternative is refusing a
					// document that is perfectly valid JSON. Past the range of a
					// double there is nothing left to fall back on, and the check
					// below refuses it rather than hand back an infinity that no
					// caller expects and that write() could not serialize anyway.
				}

			final double value = Double.parseDouble(number);
			if (Double.isInfinite(value))
				throw new IllegalArgumentException(
						"Number " + abbreviate(number) + " at position " + start + " is out of range");

			return Monomorphic.createNumber(value);
		}

		/** The integer part: at least one digit, and no leading zero. */
		private void parseIntegerPart() {
			final int at = pos;
			if (hasMore() == false || isDigit(peek()) == false)
				throw new IllegalArgumentException("Expected a digit at position " + pos);

			if (next() == '0' && hasMore() && isDigit(peek()))
				throw new IllegalArgumentException("Number at position " + at + " has a leading zero");

			while (hasMore() && isDigit(text.charAt(pos)))
				pos++;
		}

		private void parseDigits(final String what) {
			if (hasMore() == false || isDigit(peek()) == false)
				throw new IllegalArgumentException("Expected " + what + " at position " + pos);

			while (hasMore() && isDigit(text.charAt(pos)))
				pos++;
		}

	}

}
