package io.github.caspe.glib.api.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable keyed boolean state storage for card-based editors. Consuming mods
 * define the keys and defaults; G'Lib owns the state and toggle mechanics.
 */
public final class GlibBooleanStateSet {
	private final Map<String, Boolean> defaults = new LinkedHashMap<>();
	private final Map<String, Boolean> values = new LinkedHashMap<>();

	public GlibBooleanStateSet define(String key, boolean defaultValue) {
		String normalized = normalize(key);
		defaults.put(normalized, defaultValue);
		values.putIfAbsent(normalized, defaultValue);
		return this;
	}

	public boolean get(String key) {
		String normalized = normalize(key);
		return values.getOrDefault(normalized, defaults.getOrDefault(normalized, false));
	}

	public void set(String key, boolean value) {
		String normalized = requireDefined(key);
		values.put(normalized, value);
	}

	public boolean toggle(String key) {
		String normalized = requireDefined(key);
		boolean value = !get(normalized);
		values.put(normalized, value);
		return value;
	}

	public void reset(String key) {
		String normalized = requireDefined(key);
		values.put(normalized, defaults.get(normalized));
	}

	public void resetAll() {
		values.clear();
		values.putAll(defaults);
	}

	public Map<String, Boolean> snapshot() {
		return Map.copyOf(values);
	}

	private String requireDefined(String key) {
		String normalized = normalize(key);
		if (!defaults.containsKey(normalized)) {
			throw new IllegalArgumentException("Unknown editor state '" + normalized + "'");
		}
		return normalized;
	}

	private static String normalize(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("Editor state keys cannot be blank");
		}
		return key.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
