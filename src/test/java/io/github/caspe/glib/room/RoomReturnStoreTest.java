package io.github.caspe.glib.room;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomReturnStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void persistsAndClearsCrashRecoveryReturnPoints() throws Exception {
		Path file = temporaryDirectory.resolve("pending-room-returns.properties");
		UUID playerId = UUID.randomUUID();
		RoomSessionState.ReturnPoint expected = new RoomSessionState.ReturnPoint(
			World.OVERWORLD,
			new Vec3d(12.25D, 70.0D, -8.75D),
			42.0F,
			-12.5F);

		RoomReturnStore.open(file).put(playerId, expected);
		RoomReturnStore reopened = RoomReturnStore.open(file);

		assertEquals(expected, reopened.find(playerId).orElseThrow());
		assertTrue(reopened.clear(playerId));
		assertFalse(RoomReturnStore.open(file).find(playerId).isPresent());
	}

	@Test
	void rejectsNewRecordsAtTheConfiguredEntryBoundWithoutLosingExistingRecoveries()
			throws Exception {
		Path file = temporaryDirectory.resolve("bounded-entries.properties");
		RoomReturnStore store = RoomReturnStore.open(file, 2, RoomReturnStore.MAX_FILE_BYTES);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID rejected = UUID.randomUUID();
		RoomSessionState.ReturnPoint point = point(1.0D);

		store.put(first, point);
		store.put(second, point(2.0D));
		assertThrows(IOException.class, () -> store.put(rejected, point(3.0D)));

		RoomReturnStore reopened =
			RoomReturnStore.open(file, 2, RoomReturnStore.MAX_FILE_BYTES);
		assertTrue(reopened.find(first).isPresent());
		assertTrue(reopened.find(second).isPresent());
		assertFalse(reopened.find(rejected).isPresent());
	}

	@Test
	void refusesAnOversizedRewriteAndLeavesTheLastLoadableJournalIntact() throws Exception {
		Path file = temporaryDirectory.resolve("bounded-bytes.properties");
		UUID first = UUID.randomUUID();
		UUID rejected = UUID.randomUUID();
		RoomReturnStore.open(file).put(first, point(1.0D));
		long existingSize = Files.size(file);
		RoomReturnStore bounded = RoomReturnStore.open(file, 2, existingSize);

		assertThrows(IOException.class, () -> bounded.put(rejected, point(2.0D)));
		assertTrue(Files.size(file) <= existingSize);

		RoomReturnStore reopened = RoomReturnStore.open(file, 2, existingSize);
		assertTrue(reopened.find(first).isPresent());
		assertFalse(reopened.find(rejected).isPresent());
	}

	private static RoomSessionState.ReturnPoint point(double x) {
		return new RoomSessionState.ReturnPoint(
			World.OVERWORLD,
			new Vec3d(x, 70.0D, -8.75D),
			42.0F,
			-12.5F);
	}
}
