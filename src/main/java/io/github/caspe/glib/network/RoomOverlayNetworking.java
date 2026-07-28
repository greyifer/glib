package io.github.caspe.glib.network;

import io.github.caspe.glib.room.RoomRuntime;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoomOverlayNetworking {
	static final int MAX_REFRESH_REQUESTS_PER_WINDOW = 16;
	static final long REFRESH_WINDOW_NANOS = 1_000_000_000L;
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final Map<UUID, RefreshAdmission> REFRESH_ADMISSION = new ConcurrentHashMap<>();

	private RoomOverlayNetworking() {
	}

	public static void registerPayloads() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		PayloadTypeRegistry.playS2C().register(
			RoomOverlayStatePayload.ID, RoomOverlayStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(
			RoomOverlayRefreshPayload.ID, RoomOverlayRefreshPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
			RoomOverlayRefreshPayload.ID,
			(payload, context) -> {
				if (!admitRefresh(context.player().getUuid(), System.nanoTime())) {
					return;
				}
				context.server().execute(() ->
					RoomRuntime.refreshChunk(
						context.player(), payload.sessionId(), payload.chunkX(), payload.chunkZ()));
			});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			clearRefreshAdmission(handler.player.getUuid()));
	}

	public static void begin(ServerPlayerEntity player, RoomRuntime.OverlayInfo room) {
		if (!supported(player)) {
			return;
		}
		ServerPlayNetworking.send(player, new RoomOverlayStatePayload(
			room.sessionId(),
			true,
			room.anchor(),
			room.width(),
			room.height(),
			room.depth()));
	}

	public static void end(ServerPlayerEntity player, RoomRuntime.OverlayInfo room) {
		if (!supported(player)) {
			return;
		}
		ServerPlayNetworking.send(player, new RoomOverlayStatePayload(
			room.sessionId(), false, BlockPos.ORIGIN, 0, 0, 0));
	}

	public static boolean supported(ServerPlayerEntity player) {
		return ServerPlayNetworking.canSend(player, RoomOverlayStatePayload.ID)
			&& ServerPlayNetworking.canSend(player, RoomLibrarySyncPayload.ID);
	}

	static boolean admitRefresh(UUID playerId, long now) {
		boolean[] accepted = {false};
		REFRESH_ADMISSION.compute(playerId, (ignored, previous) -> {
			if (previous == null
					|| now < previous.windowStartNanos()
					|| now - previous.windowStartNanos() >= REFRESH_WINDOW_NANOS) {
				accepted[0] = true;
				return new RefreshAdmission(now, 1);
			}
			if (previous.accepted() < MAX_REFRESH_REQUESTS_PER_WINDOW) {
				accepted[0] = true;
				return new RefreshAdmission(previous.windowStartNanos(), previous.accepted() + 1);
			}
			return previous;
		});
		return accepted[0];
	}

	static void clearRefreshAdmission(UUID playerId) {
		REFRESH_ADMISSION.remove(playerId);
	}

	private record RefreshAdmission(long windowStartNanos, int accepted) {
	}
}
