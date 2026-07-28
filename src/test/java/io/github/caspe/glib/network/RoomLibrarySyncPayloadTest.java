package io.github.caspe.glib.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

final class RoomLibrarySyncPayloadTest {
	private static final RoomLibrarySyncPayload.Entry ENTRY =
			new RoomLibrarySyncPayload.Entry("room", "Room", 1, 2, 3, 4, 5);

	@Test
	void acceptsBoundedPagesAndRejectsInvalidPageMetadata() {
		List<RoomLibrarySyncPayload.Entry> maximum =
				Collections.nCopies(RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE, ENTRY);
		RoomLibrarySyncPayload payload =
				new RoomLibrarySyncPayload(7L, 1, 2, 700, true, maximum);

		assertEquals(1, payload.pageIndex());
		assertEquals(RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE, payload.rooms().size());
		assertThrows(
				IllegalArgumentException.class,
				() -> new RoomLibrarySyncPayload(7L, 2, 2, 700, true, List.of()));
		assertThrows(
				IllegalArgumentException.class,
				() -> new RoomLibrarySyncPayload(
						7L,
						0,
						1,
						513,
						false,
						Collections.nCopies(513, ENTRY)));
	}

	@Test
	void overlayStateBoundsAreValidated() {
		UUID session = UUID.randomUUID();
		RoomOverlayStatePayload active =
				new RoomOverlayStatePayload(session, true, BlockPos.ORIGIN, 64, 16, 64);
		assertEquals(64, active.width());
		assertThrows(
				IllegalArgumentException.class,
				() -> new RoomOverlayStatePayload(
						session, true, BlockPos.ORIGIN, 256, 256, 256));
		assertThrows(
				IllegalArgumentException.class,
				() -> new RoomOverlayStatePayload(
						session, false, BlockPos.ORIGIN, 1, 0, 0));
	}
}
