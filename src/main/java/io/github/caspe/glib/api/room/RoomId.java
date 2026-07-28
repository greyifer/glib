package io.github.caspe.glib.api.room;

/**
 * A path-safe stable identifier used for room files and library entries.
 */
public record RoomId(String value) implements Comparable<RoomId> {
	public RoomId {
		value = RoomValidation.roomId(value);
	}

	public static RoomId of(String value) {
		return new RoomId(value);
	}

	@Override
	public int compareTo(RoomId other) {
		return value.compareTo(other.value);
	}

	@Override
	public String toString() {
		return value;
	}
}
