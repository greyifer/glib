package io.github.caspe.glib.api.room;

/**
 * Converts a selected inclusive world-space cuboid to normalized room-local coordinates.
 * The lowest selected corner is always local {@code (0, 0, 0)}.
 */
public record RoomCoordinateSystem(int minWorldX, int minWorldY, int minWorldZ, RoomDimensions dimensions) {
	public RoomCoordinateSystem {
		java.util.Objects.requireNonNull(dimensions, "dimensions");
	}

	public static RoomCoordinateSystem fromInclusiveCorners(
			int x1, int y1, int z1, int x2, int y2, int z2) {
		int minX = Math.min(x1, x2);
		int minY = Math.min(y1, y2);
		int minZ = Math.min(z1, z2);
		int width = inclusiveLength(x1, x2, "x");
		int height = inclusiveLength(y1, y2, "y");
		int depth = inclusiveLength(z1, z2, "z");
		return new RoomCoordinateSystem(minX, minY, minZ, new RoomDimensions(width, height, depth));
	}

	public RoomPosition toLocal(int worldX, int worldY, int worldZ) {
		long localX = (long) worldX - minWorldX;
		long localY = (long) worldY - minWorldY;
		long localZ = (long) worldZ - minWorldZ;
		if (localX < 0 || localX >= dimensions.width()
				|| localY < 0 || localY >= dimensions.height()
				|| localZ < 0 || localZ >= dimensions.depth()) {
			throw new IllegalArgumentException("World position is outside the selected room bounds");
		}
		return new RoomPosition((int) localX, (int) localY, (int) localZ);
	}

	public WorldPosition toWorld(RoomPosition local) {
		dimensions.requireContains(local);
		return new WorldPosition(
				Math.addExact(minWorldX, local.x()),
				Math.addExact(minWorldY, local.y()),
				Math.addExact(minWorldZ, local.z()));
	}

	public record WorldPosition(int x, int y, int z) {
	}

	private static int inclusiveLength(int first, int second, String axis) {
		long length = Math.abs((long) first - second) + 1;
		if (length > RoomValidation.MAX_AXIS) {
			throw new IllegalArgumentException(axis + " selection is larger than " + RoomValidation.MAX_AXIS);
		}
		return (int) length;
	}
}
