package io.github.caspe.glib.api.event;

import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public final class GlibBroadcasts {
	private GlibBroadcasts() {
	}

	public static void players(MinecraftServer server, Consumer<ServerPlayerEntity> action) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			action.accept(player);
		}
	}

	public static void players(ServerWorld world, Consumer<ServerPlayerEntity> action) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			action.accept(player);
		}
	}

	public static void playersNear(ServerWorld world, Vec3d origin, double radius, Consumer<ServerPlayerEntity> action) {
		double radiusSquared = radius * radius;
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(origin) <= radiusSquared) {
				action.accept(player);
			}
		}
	}

	public static void chat(MinecraftServer server, Text message) {
		players(server, player -> player.sendMessage(message, false));
	}

	public static void actionBar(MinecraftServer server, Text message) {
		players(server, player -> player.sendMessage(message, true));
	}

	public static void emit(String event, Object payload) {
		GlibEvents.emit(event, payload);
	}
}
