package io.github.caspe.glib.api.room;

/**
 * An integer block position in room-local coordinates.
 */
public record RoomPosition(int x, int y, int z) implements Comparable<RoomPosition> {
	public RoomPosition {
		if (x < 0 || y < 0 || z < 0) {
			throw new IllegalArgumentException("Room-local coordinates must be non-negative");
		}
	}

	@Override
	public int compareTo(RoomPosition other) {
		int byY = Integer.compare(y, other.y);
		if (byY != 0) {
			return byY;
		}
		int byZ = Integer.compare(z, other.z);
		return byZ != 0 ? byZ : Integer.compare(x, other.x);
	}
}
