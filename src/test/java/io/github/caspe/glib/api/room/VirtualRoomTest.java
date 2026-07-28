package io.github.caspe.glib.api.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class VirtualRoomTest {
	@Test
	void selectedMinimumCornerBecomesLocalZeroRegardlessOfSelectionOrder() {
		RoomCoordinateSystem coordinates =
				RoomCoordinateSystem.fromInclusiveCorners(12, 80, 7, 10, 78, 5);

		assertEquals(new RoomDimensions(3, 3, 3), coordinates.dimensions());
		assertEquals(new RoomPosition(0, 0, 0), coordinates.toLocal(10, 78, 5));
		assertEquals(
				new RoomCoordinateSystem.WorldPosition(12, 80, 7),
				coordinates.toWorld(new RoomPosition(2, 2, 2)));
	}

	@Test
	void rejectsBlocksOutsideRoomAndDuplicatePositions() {
		RoomDimensions dimensions = new RoomDimensions(2, 2, 2);
		RoomBlock first = RoomBlock.of(new RoomPosition(1, 1, 1), "minecraft:stone");
		RoomBlock duplicate = RoomBlock.of(new RoomPosition(1, 1, 1), "minecraft:dirt");

		assertThrows(
				IllegalArgumentException.class,
				() -> new VirtualRoom(
						RoomId.of("test"),
						"Test",
						dimensions,
						Map.of(),
						List.of(first, duplicate),
						List.of()));
		assertThrows(
				IllegalArgumentException.class,
				() -> VirtualRoom.builder(RoomId.of("test"), "Test", dimensions)
						.block(RoomBlock.of(new RoomPosition(2, 0, 0), "minecraft:stone"))
						.build());
	}

	@Test
	void validatesPathSafeRoomIds() {
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("../escape"));
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("folder/escape"));
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("con"));
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("nul.preview"));
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("com1"));
		assertThrows(IllegalArgumentException.class, () -> RoomId.of("lpt9.backup"));
		assertEquals("safe-room_1.0", RoomId.of("safe-room_1.0").value());
	}
}
