package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.RoomPosition;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class RoomOverlaySender {
	private RoomOverlaySender() {
	}

	static void sendFull(ServerPlayerEntity player, RoomSessionState session) {
		sendSections(player, session, false, null, null);
		queueVirtualBlockEntities(player, session, null, null, false);
	}

	static void sendFullForced(ServerPlayerEntity player, RoomSessionState session) {
		sendSections(player, session, false, null, null);
		queueVirtualBlockEntities(player, session, null, null, true);
	}

	static void sendChunk(
			ServerPlayerEntity player, RoomSessionState session, int chunkX, int chunkZ) {
		sendSections(player, session, false, chunkX, chunkZ);
		queueVirtualBlockEntities(player, session, chunkX, chunkZ, true);
	}

	static void restore(ServerPlayerEntity player, RoomSessionState session) {
		sendSections(player, session, true, null, null);
		sendRealBlockEntities(player, session);
	}

	static void sendBlock(RoomSessionState session, RoomPosition local) {
		BlockPos worldPos = session.toWorld(local);
		BlockState state = session.stateAt(local);
		Packet<?> packet = new BlockUpdateS2CPacket(worldPos, state);
		for (ServerPlayerEntity member : RoomRuntime.players(session)) {
			member.networkHandler.sendPacket(packet);
		}
	}

	private static void sendSections(
			ServerPlayerEntity player,
			RoomSessionState session,
			boolean restoreRealWorld,
			@Nullable Integer onlyChunkX,
			@Nullable Integer onlyChunkZ) {
		ServerWorld world = session.world();
		Map<Long, SectionBatch> batches = new LinkedHashMap<>();
		Set<RoomPosition> positions = restoreRealWorld
			? session.touchedPositions()
			: session.visibleStates().keySet();
		for (RoomPosition local : positions) {
			BlockPos worldPos = session.toWorld(local);
			if (onlyChunkX != null && onlyChunkZ != null
					&& (worldPos.getX() >> 4 != onlyChunkX || worldPos.getZ() >> 4 != onlyChunkZ)) {
				continue;
			}
			BlockState state = restoreRealWorld
				? world.getBlockState(worldPos)
				: session.stateAt(local);
			ChunkSectionPos sectionPos = ChunkSectionPos.from(worldPos);
			SectionBatch batch = batches.computeIfAbsent(sectionPos.asLong(), ignored ->
				new SectionBatch(
					sectionPos,
					new ChunkSection(world.getRegistryManager().get(RegistryKeys.BIOME)),
					new ShortOpenHashSet()));
			int localX = ChunkSectionPos.getLocalCoord(worldPos.getX());
			int localY = ChunkSectionPos.getLocalCoord(worldPos.getY());
			int localZ = ChunkSectionPos.getLocalCoord(worldPos.getZ());
			batch.section().setBlockState(localX, localY, localZ, state, false);
			batch.positions().add(ChunkSectionPos.packLocal(worldPos));
		}
		for (SectionBatch batch : batches.values()) {
			player.networkHandler.sendPacket(
				new ChunkDeltaUpdateS2CPacket(batch.position(), batch.positions(), batch.section()));
		}
	}

	static void sendBlockEntity(
			ServerPlayerEntity player, RoomSessionState session, RoomPosition local) {
		RoomMinecraftAdapter.ResolvedBlock block = session.room().blocks().get(local);
		if (block == null || block.blockEntityNbt() == null
				|| !session.stateAt(local).equals(block.state())) {
			return;
		}
		BlockPos worldPos = session.toWorld(local);
		NbtCompound translated = block.blockEntityNbt();
		translated.putInt("x", worldPos.getX());
		translated.putInt("y", worldPos.getY());
		translated.putInt("z", worldPos.getZ());
		BlockEntity fake = BlockEntity.createFromNbt(
			worldPos, block.state(), translated, session.world().getRegistryManager());
		if (fake != null) {
			player.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(
				fake, (ignored, registries) -> translated.copy()));
		}
	}

	private static void queueVirtualBlockEntities(
			ServerPlayerEntity player,
			RoomSessionState session,
			@Nullable Integer onlyChunkX,
			@Nullable Integer onlyChunkZ,
			boolean force) {
		RoomSessionState.Participant participant = session.participants().get(player.getUuid());
		if (participant == null) {
			return;
		}
		for (Map.Entry<RoomPosition, RoomMinecraftAdapter.ResolvedBlock> entry
				: session.room().blocks().entrySet()) {
			NbtCompound savedNbt = entry.getValue().blockEntityNbt();
			if (savedNbt == null || !session.stateAt(entry.getKey()).equals(entry.getValue().state())) {
				continue;
			}
			BlockPos worldPos = session.toWorld(entry.getKey());
			if (onlyChunkX != null && onlyChunkZ != null
					&& (worldPos.getX() >> 4 != onlyChunkX || worldPos.getZ() >> 4 != onlyChunkZ)) {
				continue;
			}
			participant.queueBlockEntityRefresh(entry.getKey(), force);
		}
	}

	private static void sendRealBlockEntities(ServerPlayerEntity player, RoomSessionState session) {
		for (RoomPosition local : session.touchedPositions()) {
			BlockEntity blockEntity = session.world().getBlockEntity(session.toWorld(local));
			if (blockEntity != null) {
				player.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(blockEntity));
			}
		}
	}

	private record SectionBatch(
			ChunkSectionPos position,
			ChunkSection section,
			ShortSet positions) {
	}
}
