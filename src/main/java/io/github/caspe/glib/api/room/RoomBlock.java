package io.github.caspe.glib.api.room;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A block state at one local position. Optional block-entity data is uncompressed binary NBT.
 */
public final class RoomBlock implements Comparable<RoomBlock> {
	private final RoomPosition position;
	private final String blockId;
	private final Map<String, String> properties;
	private final byte[] blockEntityNbt;

	public RoomBlock(
			RoomPosition position,
			String blockId,
			Map<String, String> properties,
			byte[] blockEntityNbt) {
		this.position = Objects.requireNonNull(position, "position");
		this.blockId = RoomValidation.resourceId(blockId, "block id");

		TreeMap<String, String> sortedProperties = new TreeMap<>();
		Objects.requireNonNull(properties, "properties").forEach((name, value) -> {
			RoomValidation.propertyName(name);
			RoomValidation.text(value, "block-state property value", 128);
			if (sortedProperties.put(name, value) != null) {
				throw new IllegalArgumentException("Duplicate block-state property: " + name);
			}
		});
		if (sortedProperties.size() > RoomValidation.MAX_BLOCK_PROPERTIES) {
			throw new IllegalArgumentException(
					"Block state has more than " + RoomValidation.MAX_BLOCK_PROPERTIES + " properties");
		}
		this.properties = Collections.unmodifiableMap(sortedProperties);

		if (blockEntityNbt != null && blockEntityNbt.length > RoomValidation.MAX_BLOCK_ENTITY_BYTES) {
			throw new IllegalArgumentException("Block-entity NBT exceeds the per-block size limit");
		}
		this.blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.clone();
	}

	public RoomBlock(RoomPosition position, String blockId, Map<String, String> properties) {
		this(position, blockId, properties, null);
	}

	public static RoomBlock of(RoomPosition position, String blockId) {
		return new RoomBlock(position, blockId, Map.of(), null);
	}

	public RoomPosition position() {
		return position;
	}

	public String blockId() {
		return blockId;
	}

	public Map<String, String> properties() {
		return properties;
	}

	public Optional<byte[]> blockEntityNbt() {
		return blockEntityNbt == null ? Optional.empty() : Optional.of(blockEntityNbt.clone());
	}

	@Override
	public int compareTo(RoomBlock other) {
		return position.compareTo(other.position);
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof RoomBlock other)) {
			return false;
		}
		return position.equals(other.position)
				&& blockId.equals(other.blockId)
				&& properties.equals(other.properties)
				&& Arrays.equals(blockEntityNbt, other.blockEntityNbt);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(position, blockId, properties);
		return 31 * result + Arrays.hashCode(blockEntityNbt);
	}

	@Override
	public String toString() {
		return "RoomBlock[position=" + position + ", blockId=" + blockId + ", properties=" + properties
				+ ", hasBlockEntityNbt=" + (blockEntityNbt != null) + "]";
	}
}
