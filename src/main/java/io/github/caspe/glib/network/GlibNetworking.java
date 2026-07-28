package io.github.caspe.glib.network;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.caspe.glib.api.request.GlibRequests;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class GlibNetworking {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	private GlibNetworking() {
	}

	public static void registerPayloads() {
		if (REGISTERED.compareAndSet(false, true)) {
			SkinNetworking.registerPayloads();
			RoomLibraryNetworking.registerPayloads();
			RoomOverlayNetworking.registerPayloads();
			PayloadTypeRegistry.playS2C().register(TimerSyncPayload.ID, TimerSyncPayload.CODEC);
			PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC);
			PayloadTypeRegistry.playC2S().register(GlibRequestPayload.ID, GlibRequestPayload.CODEC);
			ServerPlayNetworking.registerGlobalReceiver(GlibRequestPayload.ID, (payload, context) ->
				context.server().execute(() -> GlibRequests.handle(context.server(), context.player(), payload))
			);
		}
	}
}
