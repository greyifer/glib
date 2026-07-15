package io.github.caspe.glib.api.hud;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlibHudLists {
	private static final Map<String, HudList> LISTS = new ConcurrentHashMap<>();

	private GlibHudLists() {
	}

	public static void show(String id, String title, List<Entry> entries) {
		show(id, title, entries, Anchor.MIDDLE_LEFT);
	}

	public static void show(String id, String title, List<Entry> entries, Anchor anchor) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("HUD list id cannot be blank");
		}
		LISTS.put(id, new HudList(id, title == null ? "" : title, List.copyOf(entries), anchor == null ? Anchor.MIDDLE_LEFT : anchor));
	}

	public static void clear(String id) {
		if (id != null) {
			LISTS.remove(id);
		}
	}

	public static void clearAll() {
		LISTS.clear();
	}

	public static Map<String, HudList> snapshot() {
		return Map.copyOf(LISTS);
	}

	public record Entry(String label, String value, int labelColor, int valueColor) {
		public Entry(String label, String value) {
			this(label, value, 0xFFE7EDF7, 0xFFFFFFFF);
		}
	}

	public record HudList(String id, String title, List<Entry> entries, Anchor anchor) {
	}

	public enum Anchor {
		TOP_LEFT,
		TOP_RIGHT,
		MIDDLE_LEFT,
		MIDDLE_RIGHT,
		BOTTOM_LEFT,
		BOTTOM_RIGHT
	}
}
