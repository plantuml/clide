package clide.jdtls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Truc {

	private final Map<String, Object> data;

	public Map<String, Object> getInternalMap() {
		return data;
	}

	public Truc() {
		this(new LinkedHashMap<>());
	}

	private Truc(Map<String, Object> data) {
		this.data = data;
	}

	public static Truc fromMap(Map<String, Object> parsed) {
		// Must stay null-in/null-out: every caller below (castToMap(), rangeOf(),
		// startOf()...) relies on a null check to mean "absent or wrong shape".
		// Wrapping null would hand back a Truc whose data is null - NPE later.
		return parsed == null ? null : new Truc(parsed);
	}

	public static Truc of(String key, boolean value) {
		final Truc result = new Truc();
		result.data.put(key, value);
		return result;
	}

	public boolean containsKey(String key) {
		return data.containsKey(key);
	}

	public void putTruc(String key, Truc value) {
		data.put(key, value);

	}

	public void putString(String key, String value) {
		data.put(key, value);
	}

	public void putBoolean(String key, boolean value) {
		data.put(key, value);
	}

	public void putNull(String key) {
		data.put(key, null);
	}

	public void putList(String key, List<Truc> value) {
		data.put(key, value);
	}

	public void putLong(String key, long value) {
		data.put(key, value);
	}

	public void putObject(String key, Object params) {
		data.put(key, params);
	}

	/**
	 * The raw value behind key, whatever its shape. Only for the places that
	 * genuinely have to handle several shapes (a JSON-RPC "error" object, an LSP
	 * result that is a Location or a Location[] or null, hover "contents" that is
	 * a String or a MarkupContent). Everywhere the shape is known, use the typed
	 * accessor instead.
	 */
	public Object getObject(String key) {
		return data.get(key);
	}

	/** Elements left raw - see asList() on the caller side. */
	public List<Object> getList(String key) {
		final Object value = data.get(key);
		if (value instanceof List)
			return (List<Object>) value;
		return List.of();
	}

	/** null unless the value really is a String - never a ClassCastException. */
	public String getString(String key) {
		final Object value = data.get(key);
		return value instanceof String ? (String) value : null;
	}

	public Truc getTruc(String key) {
		final Object value = data.get(key);
		if (value instanceof Map)
			return fromMap((Map<String, Object>) value);
		return (Truc) value;
	}

	public long getAsLongOrMinusOn(String key, long defValue) {
		final Object value = data.get(key);
		if (value == null)
			return defValue;
		if (value instanceof Number)
			return ((Number) value).longValue();
		return Long.parseLong(value.toString());
	}

	public List<Object> getOrDefault(String key, List<Object> defValue) {
		final Object value = data.get(key);
		if (value instanceof List)
			return (List<Object>) value;
		return defValue;
	}

}
