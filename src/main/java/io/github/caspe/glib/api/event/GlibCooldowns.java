package io.github.caspe.glib.api.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.world.ServerWorld;

public final class GlibCooldowns {
	private static final Map<String, Long> READY_AT_TICK = new ConcurrentHashMap<>();

	private GlibCooldowns() {
	}

	public static boolean tryUse(String key, long currentTick, int cooldownTicks) {
		if (!ready(key, currentTick)) {
			return false;
		}
		set(key, currentTick, cooldownTicks);
		return true;
	}

	public static boolean tryUse(String key, ServerWorld world, int cooldownTicks) {
		return tryUse(key, world.getTime(), cooldownTicks);
	}

	public static boolean ready(String key, long currentTick) {
		return remainingTicks(key, currentTick) <= 0;
	}

	public static boolean ready(String key, ServerWorld world) {
		return ready(key, world.getTime());
	}

	public static int remainingTicks(String key, long currentTick) {
		if (key == null || key.isBlank()) {
			return 0;
		}
		long readyAt = READY_AT_TICK.getOrDefault(key, 0L);
		return Math.max(0, (int) (readyAt - currentTick));
	}

	public static int remainingTicks(String key, ServerWorld world) {
		return remainingTicks(key, world.getTime());
	}

	public static void set(String key, long currentTick, int cooldownTicks) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("Cooldown key cannot be blank");
		}
		READY_AT_TICK.put(key, currentTick + Math.max(0, cooldownTicks));
	}

	public static void reset(String key) {
		if (key != null) {
			READY_AT_TICK.remove(key);
		}
	}

	public static void clear() {
		READY_AT_TICK.clear();
	}

	public static String key(UUID owner, String action) {
		return owner + ":" + clean(action);
	}

	public static String key(String owner, String action) {
		return clean(owner) + ":" + clean(action);
	}

	private static String clean(String value) {
		return value == null ? "" : value;
	}
}
