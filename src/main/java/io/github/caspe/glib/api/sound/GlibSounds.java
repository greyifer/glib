package io.github.caspe.glib.api.sound;

import java.util.HashSet;
import java.util.Set;

import io.github.caspe.glib.api.event.GlibBroadcasts;
import io.github.caspe.glib.api.event.GlibCooldowns;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;

public final class GlibSounds {
	private GlibSounds() {
	}

	public static void play(ServerWorld world, Vec3d pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		world.playSound(null, pos.x, pos.y, pos.z, sound, category, volume, pitch);
	}

	public static void play(ServerPlayerEntity player, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		play(player.getServerWorld(), player.getPos(), sound, category, volume, pitch);
	}

	public static void broadcast(MinecraftServer server, Vec3d pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		Set<ServerWorld> worlds = new HashSet<>();
		GlibBroadcasts.players(server, player -> worlds.add(player.getServerWorld()));
		for (ServerWorld world : worlds) {
			play(world, pos, sound, category, volume, pitch);
		}
	}

	public static void broadcastNear(ServerWorld world, Vec3d pos, double radius, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		GlibBroadcasts.playersNear(world, pos, radius, player -> play(player.getServerWorld(), pos, sound, category, volume, pitch));
	}

	public static boolean playWithCooldown(String cooldownKey, ServerWorld world, int cooldownTicks, Vec3d pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		if (!GlibCooldowns.tryUse(cooldownKey, world, cooldownTicks)) {
			return false;
		}
		play(world, pos, sound, category, volume, pitch);
		return true;
	}

	public static boolean broadcastWithCooldown(String cooldownKey, MinecraftServer server, long currentTick, int cooldownTicks, Vec3d pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		if (!GlibCooldowns.tryUse(cooldownKey, currentTick, cooldownTicks)) {
			return false;
		}
		broadcast(server, pos, sound, category, volume, pitch);
		return true;
	}
}
