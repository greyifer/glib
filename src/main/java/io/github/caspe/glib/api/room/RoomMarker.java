package io.github.caspe.glib.api.room;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A named local spawn point, hotspot, or application-defined point.
 */
public record RoomMarker(
		String id,
		Kind kind,
		RoomPosition position,
		float yaw,
		float pitch,
		Map<String, String> metadata) implements Comparable<RoomMarker> {

	public RoomMarker {
		id = RoomValidation.markerId(id);
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(position, "position");
		if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
			throw new IllegalArgumentException("Marker rotation must be finite and pitch must be between -90 and 90");
		}
		yaw = normalizeYaw(yaw);

		TreeMap<String, String> copy = new TreeMap<>();
		Objects.requireNonNull(metadata, "metadata").forEach((key, value) -> {
			RoomValidation.metadataKey(key);
			RoomValidation.text(value, "marker metadata value", 4096);
			copy.put(key, value);
		});
		if (copy.size() > RoomValidation.MAX_METADATA_ENTRIES) {
			throw new IllegalArgumentException("Too many marker metadata entries");
		}
		metadata = Collections.unmodifiableMap(copy);
	}

	public RoomMarker(String id, Kind kind, RoomPosition position) {
		this(id, kind, position, 0.0F, 0.0F, Map.of());
	}

	@Override
	public int compareTo(RoomMarker other) {
		return id.compareTo(other.id);
	}

	public enum Kind {
		SPAWN(0),
		HOTSPOT(1),
		CUSTOM(2);

		private final int wireCode;

		Kind(int wireCode) {
			this.wireCode = wireCode;
		}

		int wireCode() {
			return wireCode;
		}

		static Kind fromWireCode(int wireCode) {
			return switch (wireCode) {
				case 0 -> SPAWN;
				case 1 -> HOTSPOT;
				case 2 -> CUSTOM;
				default -> throw new IllegalArgumentException("Unknown room marker kind: " + wireCode);
			};
		}
	}

	private static float normalizeYaw(float value) {
		float normalized = value % 360.0F;
		if (normalized >= 180.0F) {
			normalized -= 360.0F;
		} else if (normalized < -180.0F) {
			normalized += 360.0F;
		}
		return normalized == -0.0F ? 0.0F : normalized;
	}
}
