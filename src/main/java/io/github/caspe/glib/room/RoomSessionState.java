package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.RoomDimensions;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.RoomMarker;
import io.github.caspe.glib.api.room.RoomPosition;
import io.github.caspe.glib.api.room.RoomSessionInfo;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class RoomSessionState {
	private final UUID id;
	private RoomMinecraftAdapter.ResolvedRoom room;
	private final ServerWorld world;
	private BlockPos anchor;
	private final Map<UUID, Participant> participants = new LinkedHashMap<>();
	private final Map<RoomPosition, BlockState> overrides = new LinkedHashMap<>();
	private final Map<RoomPosition, Long> scheduledResets = new LinkedHashMap<>();
	private final Map<RoomPosition, Long> scheduledCorrections = new LinkedHashMap<>();
	private int hostScanCursor;
	private long revision = 1L;

	RoomSessionState(
			UUID id,
			RoomMinecraftAdapter.ResolvedRoom room,
			ServerWorld world,
			BlockPos anchor) {
		this.id = id;
		this.room = room;
		this.world = world;
		this.anchor = anchor.toImmutable();
	}

	UUID id() {
		return id;
	}

	RoomId roomId() {
		return room.saved().id();
	}

	RoomMinecraftAdapter.ResolvedRoom room() {
		return room;
	}

	ServerWorld world() {
		return world;
	}

	BlockPos anchor() {
		return anchor;
	}

	long revision() {
		return revision;
	}

	RoomDimensions dimensions() {
		return room.saved().dimensions();
	}

	Set<UUID> members() {
		return Set.copyOf(participants.keySet());
	}

	Map<UUID, Participant> participants() {
		return participants;
	}

	RoomSessionInfo info() {
		return new RoomSessionInfo(
			id,
			roomId(),
			room.saved().displayName(),
			world.getRegistryKey(),
			anchor,
			revision,
			new LinkedHashSet<>(participants.keySet()));
	}

	void add(ServerPlayerEntity player, ReturnPoint returnPoint, long tick) {
		participants.put(player.getUuid(), new Participant(returnPoint, tick));
	}

	@Nullable
	Participant remove(UUID playerId) {
		return participants.remove(playerId);
	}

	boolean isEmpty() {
		return participants.isEmpty();
	}

	boolean containsWorld(BlockPos position) {
		int x = position.getX() - anchor.getX();
		int y = position.getY() - anchor.getY();
		int z = position.getZ() - anchor.getZ();
		return x >= 0 && x < dimensions().width()
			&& y >= 0 && y < dimensions().height()
			&& z >= 0 && z < dimensions().depth();
	}

	Optional<RoomPosition> toLocal(BlockPos position) {
		if (!containsWorld(position)) {
			return Optional.empty();
		}
		return Optional.of(new RoomPosition(
			position.getX() - anchor.getX(),
			position.getY() - anchor.getY(),
			position.getZ() - anchor.getZ()));
	}

	Vec3d toLocal(Vec3d position) {
		return position.subtract(anchor.getX(), anchor.getY(), anchor.getZ());
	}

	Vec3d toWorld(Vec3d position) {
		return position.add(anchor.getX(), anchor.getY(), anchor.getZ());
	}

	BlockPos toWorld(RoomPosition position) {
		dimensions().requireContains(position);
		return anchor.add(position.x(), position.y(), position.z());
	}

	BlockState stateAt(RoomPosition position) {
		BlockState override = overrides.get(position);
		return override == null ? room.stateAt(position) : override;
	}

	@Nullable
	RoomMinecraftAdapter.ResolvedBlock baseBlock(RoomPosition position) {
		return room.blocks().get(position);
	}

	void setState(RoomPosition position, BlockState state) {
		dimensions().requireContains(position);
		if (state.equals(room.stateAt(position))) {
			overrides.remove(position);
		} else {
			overrides.put(position, state);
		}
		revision++;
	}

	void scheduleReset(RoomPosition position, long tick) {
		scheduledResets.put(position, tick);
	}

	Map<RoomPosition, Long> scheduledResets() {
		return scheduledResets;
	}

	void scheduleCorrection(RoomPosition position, long tick) {
		scheduledCorrections.put(position, tick);
	}

	Map<RoomPosition, Long> scheduledCorrections() {
		return scheduledCorrections;
	}

	Map<RoomPosition, BlockState> visibleStates() {
		Map<RoomPosition, BlockState> visible = new LinkedHashMap<>();
		for (Map.Entry<RoomPosition, RoomMinecraftAdapter.ResolvedBlock> entry : room.blocks().entrySet()) {
			if (!entry.getValue().state().isAir()) {
				visible.put(entry.getKey(), entry.getValue().state());
			}
		}
		for (Map.Entry<RoomPosition, BlockState> entry : overrides.entrySet()) {
			if (entry.getValue().isAir()) {
				visible.remove(entry.getKey());
			} else {
				visible.put(entry.getKey(), entry.getValue());
			}
		}
		return Map.copyOf(visible);
	}

	Set<RoomPosition> touchedPositions() {
		Set<RoomPosition> touched = new LinkedHashSet<>(room.blocks().keySet());
		touched.addAll(overrides.keySet());
		return Set.copyOf(touched);
	}

	int hostScanCursor() {
		return hostScanCursor;
	}

	void hostScanCursor(int cursor) {
		this.hostScanCursor = cursor;
	}

	void replaceRoom(RoomMinecraftAdapter.ResolvedRoom replacement, BlockPos replacementAnchor) {
		this.room = replacement;
		this.anchor = replacementAnchor.toImmutable();
		overrides.clear();
		scheduledResets.clear();
		scheduledCorrections.clear();
		hostScanCursor = 0;
		revision++;
	}

	RoomMarker spawnMarker(@Nullable String markerId) {
		if (markerId != null) {
			for (RoomMarker marker : room.saved().markers()) {
				if (marker.kind() == RoomMarker.Kind.SPAWN && marker.id().equals(markerId)) {
					return marker;
				}
			}
		}
		return room.spawnMarkers().stream().findFirst().orElseGet(room::fallbackSpawn);
	}

	Optional<RoomMarker> markerAt(RoomPosition position) {
		return room.saved().markers().stream()
			.filter(marker -> marker.kind() == RoomMarker.Kind.HOTSPOT)
			.filter(marker -> marker.position().equals(position))
			.findFirst();
	}

	Box hostBox(int margin) {
		return new Box(
			anchor.getX() - margin,
			anchor.getY() - margin,
			anchor.getZ() - margin,
			anchor.getX() + dimensions().width() + margin,
			anchor.getY() + dimensions().height() + margin,
			anchor.getZ() + dimensions().depth() + margin);
	}

	record ReturnPoint(
			RegistryKey<World> world,
			Vec3d position,
			float yaw,
			float pitch) {
	}

	static final class Participant {
		private final ReturnPoint returnPoint;
		private long joinedTick;
		private boolean coordinateHud;
		private int overlayPass;
		private final RoomChunkRefreshQueue chunkRefreshes = new RoomChunkRefreshQueue();
		private final Set<RoomPosition> pendingBlockEntityRefreshes = new LinkedHashSet<>();
		private final Set<RoomPosition> sentBlockEntities = new LinkedHashSet<>();

		private Participant(ReturnPoint returnPoint, long joinedTick) {
			this.returnPoint = returnPoint;
			this.joinedTick = joinedTick;
			chunkRefreshes.reset(joinedTick);
		}

		ReturnPoint returnPoint() {
			return returnPoint;
		}

		long joinedTick() {
			return joinedTick;
		}

		boolean coordinateHud() {
			return coordinateHud;
		}

		void coordinateHud(boolean enabled) {
			this.coordinateHud = enabled;
		}

		int overlayPass() {
			return overlayPass;
		}

		void overlayPass(int pass) {
			this.overlayPass = pass;
		}

		void resetOverlay(long tick) {
			joinedTick = tick;
			overlayPass = 0;
			chunkRefreshes.reset(tick);
			pendingBlockEntityRefreshes.clear();
			sentBlockEntities.clear();
		}

		void queueChunkRefresh(int chunkX, int chunkZ, long tick) {
			chunkRefreshes.queue(chunkX, chunkZ, tick);
		}

		Optional<ChunkRefresh> pollChunkRefresh(long tick) {
			return chunkRefreshes.poll(tick)
				.map(refresh -> new ChunkRefresh(refresh.x(), refresh.z()));
		}

		void queueBlockEntityRefresh(RoomPosition position, boolean force) {
			if (force) {
				sentBlockEntities.remove(position);
			}
			if (sentBlockEntities.contains(position)
					|| pendingBlockEntityRefreshes.size() >= RoomMinecraftAdapter.MAX_BLOCK_ENTITIES) {
				return;
			}
			pendingBlockEntityRefreshes.add(position);
		}

		Optional<RoomPosition> pollBlockEntityRefresh() {
			if (pendingBlockEntityRefreshes.isEmpty()) {
				return Optional.empty();
			}
			RoomPosition position = pendingBlockEntityRefreshes.iterator().next();
			pendingBlockEntityRefreshes.remove(position);
			sentBlockEntities.add(position);
			return Optional.of(position);
		}

		record ChunkRefresh(int x, int z) {
		}
	}
}
