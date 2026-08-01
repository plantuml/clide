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
		return new Truc(parsed);
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

	public String getString(String key) {
		return (String) data.get(key);
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
