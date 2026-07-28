package io.github.caspe.glib.client.room;

import io.github.caspe.glib.network.RoomOverlayRefreshPayload;
import io.github.caspe.glib.network.RoomOverlayStatePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientRoomOverlay {
	private static volatile State active;

	private ClientRoomOverlay() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(
			RoomOverlayStatePayload.ID,
			(payload, context) -> context.client().execute(() -> {
				if (payload.active()) {
					active = new State(
						payload.sessionId(),
						payload.anchor().getX(),
						payload.anchor().getZ(),
						payload.width(),
						payload.depth());
				} else {
					State current = active;
					if (current != null && current.sessionId().equals(payload.sessionId())) {
						active = null;
					}
				}
			}));
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
			requestChunk(chunk.getPos().x, chunk.getPos().z));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = null);
	}

	public static boolean isActive() {
		return active != null;
	}

	private static void requestChunk(int chunkX, int chunkZ) {
		State room = active;
		if (room == null || !room.intersects(chunkX, chunkZ)
				|| !ClientPlayNetworking.canSend(RoomOverlayRefreshPayload.ID)) {
			return;
		}
		ClientPlayNetworking.send(new RoomOverlayRefreshPayload(
			room.sessionId(), chunkX, chunkZ));
	}

	private record State(
			java.util.UUID sessionId,
			int minX,
			int minZ,
			int width,
			int depth) {
		private boolean intersects(int chunkX, int chunkZ) {
			int chunkMinX = chunkX << 4;
			int chunkMinZ = chunkZ << 4;
			int chunkMaxX = chunkMinX + 15;
			int chunkMaxZ = chunkMinZ + 15;
			return chunkMaxX >= minX && chunkMinX < minX + width
				&& chunkMaxZ >= minZ && chunkMinZ < minZ + depth;
		}
	}
}
