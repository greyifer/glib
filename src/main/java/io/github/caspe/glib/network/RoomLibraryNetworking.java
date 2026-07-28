package io.github.caspe.glib.network;

import io.github.caspe.glib.api.room.VirtualRoom;
import io.github.caspe.glib.room.RoomRuntime;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoomLibraryNetworking {
	private static final int MAX_SYNCED_ROOMS =
		Math.min(
			2_048,
			RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE * RoomLibrarySyncPayload.MAX_PAGES);
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static long revision;

	private RoomLibraryNetworking() {
	}

	public static void registerPayloads() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		PayloadTypeRegistry.playS2C().register(
			RoomLibrarySyncPayload.ID, RoomLibrarySyncPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(
			RoomLibraryRequestPayload.ID, RoomLibraryRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
			RoomLibraryRequestPayload.ID,
			(payload, context) -> {
				long now = System.nanoTime();
				boolean[] accepted = {false};
				LAST_REQUEST.compute(context.player().getUuid(), (ignored, previous) -> {
					if (previous == null || now - previous >= 1_000_000_000L) {
						accepted[0] = true;
						return now;
					}
					return previous;
				});
				if (accepted[0]) {
					context.server().execute(() -> send(context.player()));
				}
			});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			LAST_REQUEST.remove(handler.player.getUuid()));
	}

	public static void broadcast(MinecraftServer server) {
		revision++;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			send(player);
		}
	}

	public static void send(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, RoomLibrarySyncPayload.ID)) {
			return;
		}
		List<RoomLibrarySyncPayload.Entry> entries;
		int totalRooms;
		try {
			List<VirtualRoom> compatible = RoomRuntime.compatibleRooms(player.getServer());
			totalRooms = compatible.size();
			entries = compatible.stream()
				.limit(MAX_SYNCED_ROOMS)
				.map(RoomLibraryNetworking::entry)
				.toList();
		} catch (IllegalStateException exception) {
			return;
		}
		boolean truncated = totalRooms > entries.size();
		int pageCount = Math.max(
			1,
			(entries.size() + RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE - 1)
				/ RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE);
		for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
			int from = pageIndex * RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE;
			int to = Math.min(entries.size(), from + RoomLibrarySyncPayload.MAX_ENTRIES_PER_PAGE);
			ServerPlayNetworking.send(player, new RoomLibrarySyncPayload(
				revision,
				pageIndex,
				pageCount,
				totalRooms,
				truncated,
				entries.subList(from, to)));
		}
	}

	private static RoomLibrarySyncPayload.Entry entry(VirtualRoom room) {
		return new RoomLibrarySyncPayload.Entry(
			room.id().value(),
			room.displayName(),
			room.dimensions().width(),
			room.dimensions().height(),
			room.dimensions().depth(),
			room.blocks().size(),
			room.markers().size());
	}
}
