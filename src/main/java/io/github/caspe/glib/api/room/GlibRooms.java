package io.github.caspe.glib.api.room;

import io.github.caspe.glib.room.RoomRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level server API for G'Lib virtual rooms.
 */
public final class GlibRooms {
	private GlibRooms() {
	}

	/**
	 * Initializes lifecycle hooks and commands. G'Lib calls this automatically.
	 */
	public static void initialize() {
		RoomRuntime.register();
	}

	public static GlibRoomLibrary library(MinecraftServer server) {
		return RoomRuntime.library(server);
	}

	public static List<RoomSessionInfo> sessions(MinecraftServer server) {
		return RoomRuntime.sessions(server);
	}

	public static Optional<RoomSessionInfo> session(ServerPlayerEntity player) {
		return RoomRuntime.session(player);
	}

	public static RoomSessionInfo enter(ServerPlayerEntity player, RoomId roomId) throws IOException {
		return RoomRuntime.enter(player, roomId);
	}

	public static RoomSessionInfo join(
			ServerPlayerEntity player, UUID sessionId, String spawnMarker) throws IOException {
		return RoomRuntime.join(player, sessionId, spawnMarker);
	}

	public static boolean leave(ServerPlayerEntity player) {
		return RoomRuntime.leave(player);
	}

	/**
	 * Returns precise feet coordinates relative to the room's minimum corner.
	 */
	public static Optional<Vec3d> localPosition(ServerPlayerEntity player) {
		return RoomRuntime.localPosition(player);
	}

	public static Optional<RoomPosition> localBlockPosition(ServerPlayerEntity player) {
		return RoomRuntime.localBlockPosition(player);
	}

	public static Optional<Vec3d> toWorld(ServerPlayerEntity player, Vec3d localPosition) {
		return RoomRuntime.toWorld(player, localPosition);
	}

	public static boolean teleportLocal(
			ServerPlayerEntity player,
			double x,
			double y,
			double z,
			float yaw,
			float pitch) {
		return RoomRuntime.teleportLocal(player, x, y, z, yaw, pitch);
	}

	public static boolean teleportMarker(ServerPlayerEntity player, String markerId) {
		return RoomRuntime.teleportMarker(player, markerId);
	}

	public static boolean coordinateHud(ServerPlayerEntity player, boolean enabled) {
		return RoomRuntime.coordinateHud(player, enabled);
	}

	public static boolean coordinateHud(ServerPlayerEntity player) {
		return RoomRuntime.coordinateHud(player);
	}

	/**
	 * Applies a session-local visual and collision state for every member.
	 */
	public static boolean setVisualBlock(
			ServerPlayerEntity member, RoomPosition position, BlockState state) {
		return RoomRuntime.setVisualBlock(member, position, state);
	}

	public static boolean resetVisualBlock(
			ServerPlayerEntity member, RoomPosition position) {
		return RoomRuntime.resetVisualBlock(member, position);
	}

	public static VirtualRoom capture(
			ServerPlayerEntity author,
			BlockPos first,
			BlockPos second,
			RoomId id,
			String displayName,
			Collection<RoomMarker> markers) throws IOException {
		return RoomRuntime.capture(author, first, second, id, displayName, markers);
	}

	public static GlibRoomLibrary.ChangeSet save(MinecraftServer server, VirtualRoom room) throws IOException {
		return RoomRuntime.save(server, room);
	}

	public static GlibRoomLibrary.ChangeSet reload(MinecraftServer server) throws IOException {
		return RoomRuntime.reload(server);
	}

	public static void resend(ServerPlayerEntity player) {
		RoomRuntime.resend(player);
	}
}
