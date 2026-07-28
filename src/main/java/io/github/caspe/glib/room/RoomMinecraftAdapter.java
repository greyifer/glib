package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.RoomBlock;
import io.github.caspe.glib.api.room.RoomCoordinateSystem;
import io.github.caspe.glib.api.room.RoomDimensions;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.RoomMarker;
import io.github.caspe.glib.api.room.RoomPosition;
import io.github.caspe.glib.api.room.VirtualRoom;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RoomMinecraftAdapter {
	static final int MAX_RUNTIME_AXIS = 256;
	static final long MAX_RUNTIME_VOLUME = 262_144L;
	static final int MAX_BLOCK_ENTITIES = 256;
	static final int MAX_BLOCK_ENTITY_BYTES = 256 * 1024;
	static final int MAX_TOTAL_BLOCK_ENTITY_BYTES = 4 * 1024 * 1024;

	private RoomMinecraftAdapter() {
	}

	static VirtualRoom capture(
			ServerWorld world,
			BlockPos first,
			BlockPos second,
			RoomId id,
			String displayName,
			@Nullable ServerPlayerEntity author,
			Collection<RoomMarker> extraMarkers) throws IOException {
		RoomCoordinateSystem coordinates = RoomCoordinateSystem.fromInclusiveCorners(
			first.getX(), first.getY(), first.getZ(),
			second.getX(), second.getY(), second.getZ());
		requireRuntimeSize(coordinates.dimensions());

		VirtualRoom.Builder builder = VirtualRoom.builder(id, displayName, coordinates)
			.metadata("created_at", Instant.now().toString())
			.metadata("source_dimension", world.getRegistryKey().getValue().toString());
		if (author != null) {
			builder.metadata("author_uuid", author.getUuidAsString())
				.metadata("author_name", author.getGameProfile().getName());
		}

		BlockPos min = new BlockPos(
			coordinates.minWorldX(), coordinates.minWorldY(), coordinates.minWorldZ());
		BlockPos max = new BlockPos(
			coordinates.minWorldX() + coordinates.dimensions().width() - 1,
			coordinates.minWorldY() + coordinates.dimensions().height() - 1,
			coordinates.minWorldZ() + coordinates.dimensions().depth() - 1);
		for (BlockPos worldPos : BlockPos.iterate(min, max)) {
			BlockState state = world.getBlockState(worldPos);
			if (state.isAir()) {
				continue;
			}
			RoomPosition local = coordinates.toLocal(worldPos.getX(), worldPos.getY(), worldPos.getZ());
			builder.block(new RoomBlock(
				local,
				Registries.BLOCK.getId(state.getBlock()).toString(),
				properties(state),
				blockEntityBytes(world, worldPos, local)));
		}

		for (RoomMarker marker : extraMarkers) {
			builder.marker(marker);
		}
		if (extraMarkers.stream().noneMatch(marker -> marker.kind() == RoomMarker.Kind.SPAWN)) {
			RoomMarker spawn = defaultSpawn(world, coordinates, author);
			builder.marker(spawn);
		}
		VirtualRoom captured = builder.build();
		resolve(captured);
		return captured;
	}

	static ResolvedRoom resolve(VirtualRoom room) throws IOException {
		requireRuntimeSize(room.dimensions());
		Map<RoomPosition, ResolvedBlock> blocks = new LinkedHashMap<>();
		int blockEntityCount = 0;
		long blockEntityBytes = 0L;
		for (RoomBlock saved : room.blocks()) {
			BlockState state = state(saved);
			NbtCompound blockEntityNbt = null;
			if (saved.blockEntityNbt().isPresent()) {
				byte[] encodedNbt = saved.blockEntityNbt().orElseThrow();
				blockEntityCount++;
				if (blockEntityCount > MAX_BLOCK_ENTITIES) {
					throw new IOException(
						"Virtual room contains more than " + MAX_BLOCK_ENTITIES + " block entities");
				}
				if (encodedNbt.length > MAX_BLOCK_ENTITY_BYTES) {
					throw new IOException(
						"Block entity at " + saved.position() + " exceeds "
							+ MAX_BLOCK_ENTITY_BYTES + " encoded bytes");
				}
				blockEntityBytes += encodedNbt.length;
				if (blockEntityBytes > MAX_TOTAL_BLOCK_ENTITY_BYTES) {
					throw new IOException(
						"Virtual-room block entities exceed "
							+ MAX_TOTAL_BLOCK_ENTITY_BYTES + " encoded bytes in total");
				}
				blockEntityNbt = readNbt(encodedNbt);
				validateBlockEntity(state, blockEntityNbt, saved.position());
			}
			blocks.put(saved.position(), new ResolvedBlock(state, blockEntityNbt));
		}
		ResolvedRoom resolved = new ResolvedRoom(room, Map.copyOf(blocks));
		boolean hasSpawn = false;
		for (RoomMarker marker : room.markers()) {
			if (marker.kind() != RoomMarker.Kind.SPAWN) {
				continue;
			}
			hasSpawn = true;
			if (!isSafeSpawn(resolved, marker.position())) {
				throw new IOException(
					"Spawn marker '" + marker.id() + "' does not have clear feet, head room, and a solid floor");
			}
		}
		if (!hasSpawn && resolved.findFallbackSpawn().isEmpty()) {
			throw new IOException(
				"Virtual room has no safe spawn; add a spawn marker above a solid virtual floor");
		}
		return resolved;
	}

	static void requireRuntimeSize(RoomDimensions dimensions) {
		if (dimensions.width() > MAX_RUNTIME_AXIS
				|| dimensions.height() > MAX_RUNTIME_AXIS
				|| dimensions.depth() > MAX_RUNTIME_AXIS) {
			throw new IllegalArgumentException(
				"Virtual room axes must be at most " + MAX_RUNTIME_AXIS + " blocks at runtime");
		}
		if (dimensions.volume() > MAX_RUNTIME_VOLUME) {
			throw new IllegalArgumentException(
				"Virtual room volume exceeds the runtime limit of " + MAX_RUNTIME_VOLUME + " blocks");
		}
	}

	private static Map<String, String> properties(BlockState state) {
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
			result.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
		}
		return result;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String propertyName(Property property, Comparable value) {
		return property.name(value);
	}

	private static BlockState state(RoomBlock saved) throws IOException {
		Identifier identifier = Identifier.tryParse(saved.blockId());
		if (identifier == null || !Registries.BLOCK.containsId(identifier)) {
			throw new IOException("Room '" + saved.position() + "' references missing block " + saved.blockId());
		}
		Block block = Registries.BLOCK.get(identifier);
		BlockState state = block.getDefaultState();
		for (Map.Entry<String, String> entry : saved.properties().entrySet()) {
			Property<?> property = block.getStateManager().getProperty(entry.getKey());
			if (property == null) {
				throw new IOException("Block " + saved.blockId() + " has no property '" + entry.getKey() + "'");
			}
			state = withProperty(state, property, entry.getValue(), saved.blockId());
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withProperty(
			BlockState state, Property<T> property, String value, String blockId) throws IOException {
		Optional<T> parsed = property.parse(value);
		if (parsed.isEmpty()) {
			throw new IOException(
				"Invalid value '" + value + "' for property '" + property.getName() + "' on " + blockId);
		}
		return state.with(property, parsed.get());
	}

	@Nullable
	private static byte[] blockEntityBytes(
			ServerWorld world, BlockPos worldPos, RoomPosition local) throws IOException {
		BlockEntity blockEntity = world.getBlockEntity(worldPos);
		if (blockEntity == null) {
			return null;
		}
		NbtCompound nbt = blockEntity.toInitialChunkDataNbt(world.getRegistryManager());
		BlockEntity.writeIdToNbt(nbt, blockEntity.getType());
		nbt.putInt("x", local.x());
		nbt.putInt("y", local.y());
		nbt.putInt("z", local.z());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			NbtIo.writeCompound(nbt, output);
		}
		return bytes.toByteArray();
	}

	private static NbtCompound readNbt(byte[] bytes) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
			NbtCompound nbt = NbtIo.readCompound(input, NbtSizeTracker.of(MAX_BLOCK_ENTITY_BYTES));
			if (input.read() != -1) {
				throw new IOException("Block-entity NBT contains trailing data");
			}
			return nbt;
		}
	}

	private static void validateBlockEntity(
			BlockState state, NbtCompound nbt, RoomPosition position) throws IOException {
		if (!state.hasBlockEntity()) {
			throw new IOException(
				"Block entity at " + position + " is attached to a block that cannot host one");
		}
		Identifier id = Identifier.tryParse(nbt.getString("id"));
		if (id == null || !Registries.BLOCK_ENTITY_TYPE.containsId(id)) {
			throw new IOException("Block entity at " + position + " has an unknown type");
		}
		BlockEntityType<?> type = Registries.BLOCK_ENTITY_TYPE.get(id);
		if (!type.supports(state)) {
			throw new IOException(
				"Block entity type " + id + " does not support the block at " + position);
		}
	}

	private static RoomMarker defaultSpawn(
			ServerWorld world,
			RoomCoordinateSystem coordinates,
			@Nullable ServerPlayerEntity author) throws IOException {
		if (author != null) {
			BlockPos feet = author.getBlockPos();
			if (feet.getX() >= coordinates.minWorldX()
					&& feet.getX() < coordinates.minWorldX() + coordinates.dimensions().width()
					&& feet.getY() >= coordinates.minWorldY()
					&& feet.getY() < coordinates.minWorldY() + coordinates.dimensions().height()
					&& feet.getZ() >= coordinates.minWorldZ()
					&& feet.getZ() < coordinates.minWorldZ() + coordinates.dimensions().depth()
					&& isSafeWorldSpawn(world, feet, coordinates)) {
				return new RoomMarker(
					"default",
					RoomMarker.Kind.SPAWN,
					coordinates.toLocal(feet.getX(), feet.getY(), feet.getZ()),
					author.getYaw(),
					author.getPitch(),
					Map.of());
			}
		}

		RoomDimensions dimensions = coordinates.dimensions();
		for (int y = 1; y < dimensions.height(); y++) {
			for (int z = 0; z < dimensions.depth(); z++) {
				for (int x = 0; x < dimensions.width(); x++) {
					int worldX = coordinates.minWorldX() + x;
					int worldY = coordinates.minWorldY() + y;
					int worldZ = coordinates.minWorldZ() + z;
					BlockPos feet = new BlockPos(worldX, worldY, worldZ);
					if (isSafeWorldSpawn(world, feet, coordinates)) {
						return new RoomMarker(
							"default", RoomMarker.Kind.SPAWN, new RoomPosition(x, y, z), 0.0F, 0.0F, Map.of());
					}
				}
			}
		}
		throw new IOException(
			"The selected build has no safe spawn above a solid floor; stand at one or add a spawn marker");
	}

	private static boolean isSafeWorldSpawn(
			ServerWorld world, BlockPos feet, RoomCoordinateSystem coordinates) {
		RoomDimensions dimensions = coordinates.dimensions();
		int localY = feet.getY() - coordinates.minWorldY();
		if (localY <= 0 || localY >= dimensions.height()) {
			return false;
		}
		if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
			return false;
		}
		if (localY + 1 < dimensions.height()
				&& !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
			return false;
		}
		return isStandableFloor(
			world.getBlockState(feet.down()).getCollisionShape(world, feet.down()));
	}

	private static boolean isSafeSpawn(ResolvedRoom room, RoomPosition feet) {
		if (feet.y() <= 0 || !collisionEmpty(room.stateAt(feet))) {
			return false;
		}
		if (feet.y() + 1 < room.saved().dimensions().height()
				&& !collisionEmpty(room.stateAt(new RoomPosition(feet.x(), feet.y() + 1, feet.z())))) {
			return false;
		}
		return isStandableFloor(room.stateAt(
			new RoomPosition(feet.x(), feet.y() - 1, feet.z()))
			.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN));
	}

	private static boolean collisionEmpty(BlockState state) {
		return state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
	}

	static boolean isStandableFloor(net.minecraft.util.shape.VoxelShape shape) {
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.getMax(Direction.Axis.Y);
		return top >= 0.875D && top <= 1.000_001D;
	}

	record ResolvedRoom(VirtualRoom saved, Map<RoomPosition, ResolvedBlock> blocks) {
		BlockState stateAt(RoomPosition position) {
			ResolvedBlock block = blocks.get(position);
			return block == null ? net.minecraft.block.Blocks.AIR.getDefaultState() : block.state();
		}

		List<RoomMarker> spawnMarkers() {
			List<RoomMarker> markers = new ArrayList<>();
			for (RoomMarker marker : saved.markers()) {
				if (marker.kind() == RoomMarker.Kind.SPAWN) {
					markers.add(marker);
				}
			}
			return List.copyOf(markers);
		}

		RoomMarker fallbackSpawn() {
			return findFallbackSpawn().orElseThrow(
				() -> new IllegalStateException("Resolved room does not contain a safe spawn"));
		}

		Optional<RoomMarker> findFallbackSpawn() {
			for (int y = 1; y < saved.dimensions().height(); y++) {
				for (int z = 0; z < saved.dimensions().depth(); z++) {
					for (int x = 0; x < saved.dimensions().width(); x++) {
						RoomPosition feet = new RoomPosition(x, y, z);
						if (isSafeSpawn(this, feet)) {
							return Optional.of(new RoomMarker("default", RoomMarker.Kind.SPAWN, feet));
						}
					}
				}
			}
			return Optional.empty();
		}
	}

	record ResolvedBlock(BlockState state, @Nullable NbtCompound blockEntityNbt) {
		ResolvedBlock {
			blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.copy();
		}

		@Override
		public NbtCompound blockEntityNbt() {
			return blockEntityNbt == null ? null : blockEntityNbt.copy();
		}
	}
}
