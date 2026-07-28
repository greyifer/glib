package io.github.caspe.glib.room;

import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomSpawnSafetyTest {
	@Test
	void acceptsNearFullBlockFloorsButRejectsShapesThatEnterTheFeetSpace() {
		assertTrue(RoomMinecraftAdapter.isStandableFloor(VoxelShapes.fullCube()));
		assertTrue(RoomMinecraftAdapter.isStandableFloor(
			VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 1.0D, 0.875D, 1.0D)));
		assertFalse(RoomMinecraftAdapter.isStandableFloor(VoxelShapes.empty()));
		assertFalse(RoomMinecraftAdapter.isStandableFloor(
			VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 1.0D, 1.01D, 1.0D)));
	}
}
