package clide.jdtls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer, just enough to speak JSON-RPC with jdtls. Not a
 * general-purpose library: no streaming, no custom types, no comments.
 */
public final class Json2 {

	private Json2() {
	}

	// ------------------------------------------------------------------
	// Writing
	// ------------------------------------------------------------------

	public static String writeTruc(final Truc value) {
		final StringBuilder out = new StringBuilder();
		writeValue(value.getInternalMap(), out);
		return out.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeValue(final Object value, final StringBuilder out) {
		if (value == null)
			out.append("null");
		else if (value instanceof String)
			writeString((String) value, out);
		else if (value instanceof Boolean || value instanceof Number)
			out.append(value.toString());
		else if (value instanceof Map)
			writeObject((Map<String, Object>) value, out);
		else if (value instanceof Truc)
			writeObject(((Truc) value).getInternalMap(), out);
		else if (value instanceof List)
			writeArray((List<Object>) value, out);
		else
			throw new IllegalArgumentException("Cannot serialize value of type " + value.getClass());
	}

	private static void writeObject(final Map<String, Object> map, final StringBuilder out) {
		out.append('{');
		boolean first = true;
		for (final Map.Entry<String, Object> entry : map.entrySet()) {
			if (first == false)
				out.append(',');
			first = false;
			writeString(entry.getKey(), out);
			out.append(':');
			writeValue(entry.getValue(), out);
		}
		out.append('}');
	}

	private static void writeArray(final List<Object> list, final StringBuilder out) {
		out.append('[');
		boolean first = true;
		for (final Object item : list) {
			if (first == false)
				out.append(',');
			first = false;
			writeValue(item, out);
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
			default:
				if (c < 0x20)
					out.append(String.format("\\u%04x", (int) c));
				else
					out.append(c);
			}
		}
		out.append('"');
	}

	// ------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------

	public static Object parse(final String text) {
		final Parser parser = new Parser(text);
		final Object value = parser.parseValue();
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

		private char peek() {
			return text.charAt(pos);
		}

		private void skipWhitespace() {
			while (hasMore() && Character.isWhitespace(peek()))
				pos++;
		}

		private Object parseValue() {
			skipWhitespace();
			final char c = peek();
			if (c == '{')
				return parseObject();
			if (c == '[')
				return parseArray();
			if (c == '"')
				return parseString();
			if (c == 't') {
				expectLiteral("true");
				return Boolean.TRUE;
			}
			if (c == 'f') {
				expectLiteral("false");
				return Boolean.FALSE;
			}
			if (c == 'n') {
				expectLiteral("null");
				return null;
			}
			return parseNumber();
		}

		private void expectLiteral(final String literal) {
			if (text.regionMatches(pos, literal, 0, literal.length()) == false)
				throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);
			pos += literal.length();
		}

		private Map<String, Object> parseObject() {
			final Map<String, Object> map = new LinkedHashMap<>();
			pos++; // '{'
			skipWhitespace();
			if (hasMore() && peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWhitespace();
				final String key = parseString();
				skipWhitespace();
				if (peek() != ':')
					throw new IllegalArgumentException("Expected ':' at position " + pos);
				pos++;
				final Object value = parseValue();
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
			return map;
		}

		private List<Object> parseArray() {
			final List<Object> list = new ArrayList<>();
			pos++; // '['
			skipWhitespace();
			if (hasMore() && peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				final Object value = parseValue();
				list.add(value);
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
			return list;
		}

		private String parseString() {
			if (peek() != '"')
				throw new IllegalArgumentException("Expected '\"' at position " + pos);
			pos++;
			final StringBuilder out = new StringBuilder();
			while (true) {
				final char c = text.charAt(pos++);
				if (c == '"')
					break;
				if (c == '\\') {
					final char escaped = text.charAt(pos++);
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
						final String hex = text.substring(pos, pos + 4);
						pos += 4;
						out.append((char) Integer.parseInt(hex, 16));
						break;
					default:
						throw new IllegalArgumentException("Unknown escape sequence \\" + escaped);
					}
				} else
					out.append(c);
			}
			return out.toString();
		}

		private Object parseNumber() {
			final int start = pos;
			if (peek() == '-')
				pos++;
			while (hasMore() && Character.isDigit(peek()))
				pos++;
			boolean isDouble = false;
			if (hasMore() && peek() == '.') {
				isDouble = true;
				pos++;
				while (hasMore() && Character.isDigit(peek()))
					pos++;
			}
			if (hasMore() && (peek() == 'e' || peek() == 'E')) {
				isDouble = true;
				pos++;
				if (hasMore() && (peek() == '+' || peek() == '-'))
					pos++;
				while (hasMore() && Character.isDigit(peek()))
					pos++;
			}
			final String number = text.substring(start, pos);
			if (isDouble)
				return Double.parseDouble(number);
			try {
				return Long.parseLong(number);
			} catch (final NumberFormatException e) {
				return Double.parseDouble(number);
			}
		}

	}

}
