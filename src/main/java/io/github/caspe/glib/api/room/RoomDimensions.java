package io.github.caspe.glib.api.room;

/**
 * Positive room dimensions. Each axis is limited to 4096 blocks.
 */
public record RoomDimensions(int width, int height, int depth) {
	public RoomDimensions {
		validateAxis(width, "width");
		validateAxis(height, "height");
		validateAxis(depth, "depth");
	}

	public long volume() {
		return (long) width * height * depth;
	}

	public boolean contains(RoomPosition position) {
		return position.x() < width && position.y() < height && position.z() < depth;
	}

	public void requireContains(RoomPosition position) {
		if (!contains(position)) {
			throw new IllegalArgumentException("Position " + position + " is outside " + this);
		}
	}

	private static void validateAxis(int value, String name) {
		if (value < 1 || value > RoomValidation.MAX_AXIS) {
			throw new IllegalArgumentException(name + " must be between 1 and " + RoomValidation.MAX_AXIS);
		}
	}
}
