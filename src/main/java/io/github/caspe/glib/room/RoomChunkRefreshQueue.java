package io.github.caspe.glib.room;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded server-authoritative replay queue for a room participant.
 */
final class RoomChunkRefreshQueue {
	static final int MAX_PENDING = 256;
	static final int MAX_REFRESHES_PER_WINDOW = 4;
	static final long WINDOW_TICKS = 20L;
	static final long CHUNK_COOLDOWN_TICKS = 100L;

	private final Set<Long> pending = new LinkedHashSet<>();
	private final Map<Long, Long> nextAllowedTicks = new LinkedHashMap<>();
	private long windowStart;
	private int refreshCount;

	void reset(long tick) {
		windowStart = tick;
		refreshCount = 0;
		pending.clear();
		nextAllowedTicks.clear();
	}

	boolean queue(int chunkX, int chunkZ, long tick) {
		long packed = pack(chunkX, chunkZ);
		if (pending.contains(packed)
				|| tick < nextAllowedTicks.getOrDefault(packed, Long.MIN_VALUE)
				|| pending.size() >= MAX_PENDING) {
			return false;
		}
		pending.add(packed);
		return true;
	}

	Optional<ChunkRefresh> poll(long tick) {
		if (tick < windowStart || tick - windowStart >= WINDOW_TICKS) {
			windowStart = tick;
			refreshCount = 0;
		}
		if (refreshCount >= MAX_REFRESHES_PER_WINDOW || pending.isEmpty()) {
			return Optional.empty();
		}
		long packed = pending.iterator().next();
		pending.remove(packed);
		nextAllowedTicks.put(packed, tick + CHUNK_COOLDOWN_TICKS);
		refreshCount++;
		return Optional.of(new ChunkRefresh((int) (packed >> 32), (int) packed));
	}

	int pendingCount() {
		return pending.size();
	}

	private static long pack(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}

	record ChunkRefresh(int x, int z) {
	}
}
