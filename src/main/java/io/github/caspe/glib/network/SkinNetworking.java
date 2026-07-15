package io.github.caspe.glib.network;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.caspe.glib.api.skin.SkinSyncSnapshot;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class SkinNetworking {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	private SkinNetworking() {
	}

	public static void registerPayloads() {
		if (REGISTERED.compareAndSet(false, true)) {
			PayloadTypeRegistry.playS2C().register(SkinSyncPayload.ID, SkinSyncPayload.CODEC);
		}
	}

	public static void send(ServerPlayerEntity target, SkinSyncSnapshot snapshot) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(snapshot, "snapshot");
		ServerPlayNetworking.send(target, new SkinSyncPayload(snapshot));
	}

	public static void broadcast(MinecraftServer server, SkinSyncSnapshot snapshot) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(snapshot, "snapshot");
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			send(player, snapshot);
		}
	}
}
