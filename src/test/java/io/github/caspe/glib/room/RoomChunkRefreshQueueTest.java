package io.github.caspe.glib.room;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomChunkRefreshQueueTest {
	@Test
	void enforcesCooldownFromTheLastAuthoritativeReplay() {
		RoomChunkRefreshQueue queue = new RoomChunkRefreshQueue();
		queue.reset(0L);

		assertTrue(queue.queue(7, -4, 0L));
		assertFalse(queue.queue(7, -4, 0L));
		assertEquals(new RoomChunkRefreshQueue.ChunkRefresh(7, -4), queue.poll(0L).orElseThrow());
		assertFalse(queue.queue(7, -4, RoomChunkRefreshQueue.CHUNK_COOLDOWN_TICKS - 1L));
		assertTrue(queue.queue(7, -4, RoomChunkRefreshQueue.CHUNK_COOLDOWN_TICKS));
	}

	@Test
	void limitsAuthoritativeReplaysPerTickWindow() {
		RoomChunkRefreshQueue queue = new RoomChunkRefreshQueue();
		queue.reset(40L);
		for (int chunk = 0; chunk < RoomChunkRefreshQueue.MAX_REFRESHES_PER_WINDOW + 1; chunk++) {
			assertTrue(queue.queue(chunk, 0, 40L));
		}

		for (int sent = 0; sent < RoomChunkRefreshQueue.MAX_REFRESHES_PER_WINDOW; sent++) {
			assertTrue(queue.poll(40L).isPresent());
		}
		assertTrue(queue.poll(59L).isEmpty());
		assertTrue(queue.poll(60L).isPresent());
	}

	@Test
	void boundsPendingRefreshes() {
		RoomChunkRefreshQueue queue = new RoomChunkRefreshQueue();
		queue.reset(0L);
		for (int chunk = 0; chunk < RoomChunkRefreshQueue.MAX_PENDING; chunk++) {
			assertTrue(queue.queue(chunk, 0, 0L));
		}
		assertEquals(RoomChunkRefreshQueue.MAX_PENDING, queue.pendingCount());
		assertFalse(queue.queue(RoomChunkRefreshQueue.MAX_PENDING, 0, 0L));
	}
}
