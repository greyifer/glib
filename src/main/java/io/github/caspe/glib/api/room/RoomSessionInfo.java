package io.github.caspe.glib.api.room;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable snapshot of one running multiplayer virtual-room instance.
 */
public record RoomSessionInfo(
		UUID sessionId,
		RoomId roomId,
		String displayName,
		RegistryKey<World> world,
		BlockPos anchor,
		long revision,
		Set<UUID> members) {
	public RoomSessionInfo {
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(world, "world");
		anchor = Objects.requireNonNull(anchor, "anchor").toImmutable();
		members = Set.copyOf(Objects.requireNonNull(members, "members"));
	}
}
