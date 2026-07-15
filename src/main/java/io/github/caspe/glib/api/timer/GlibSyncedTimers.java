package io.github.caspe.glib.api.timer;

import java.util.Objects;

import io.github.caspe.glib.network.TimerSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class GlibSyncedTimers {
	public static final String DEFAULT_CHANNEL = "default";

	private GlibSyncedTimers() {
	}

	public static void send(ServerPlayerEntity target, String label, int remainingTicks) {
		send(target, DEFAULT_CHANNEL, "", label, remainingTicks, true);
	}

	public static void send(ServerPlayerEntity target, String channel, String phase, String label, int remainingTicks, boolean visible) {
		Objects.requireNonNull(target, "target");
		if (ServerPlayNetworking.canSend(target, TimerSyncPayload.ID)) {
			ServerPlayNetworking.send(target, new TimerSyncPayload(clean(channel), clean(phase), clean(label), Math.max(0, remainingTicks), visible));
		}
	}

	public static void broadcast(MinecraftServer server, String label, int remainingTicks) {
		broadcast(server, DEFAULT_CHANNEL, "", label, remainingTicks, true);
	}

	public static void broadcast(MinecraftServer server, String channel, String phase, String label, int remainingTicks, boolean visible) {
		Objects.requireNonNull(server, "server");
		TimerSyncPayload payload = new TimerSyncPayload(clean(channel), clean(phase), clean(label), Math.max(0, remainingTicks), visible);
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(player, TimerSyncPayload.ID)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	public static void broadcast(MinecraftServer server, GlibRoundTimer.Snapshot snapshot) {
		broadcast(server, DEFAULT_CHANNEL, snapshot.phase(), snapshot.label(), snapshot.remainingTicks(), snapshot.visible());
	}

	public static void hide(MinecraftServer server) {
		broadcast(server, DEFAULT_CHANNEL, "", "", 0, false);
	}

	private static String clean(String value) {
		return value == null ? "" : value;
	}
}
