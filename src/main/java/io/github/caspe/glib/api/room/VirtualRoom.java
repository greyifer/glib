package io.github.caspe.glib.api.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, renderer-independent room content stored by a {@code .gsave} file.
 */
public final class VirtualRoom {
	private final RoomId id;
	private final String displayName;
	private final RoomDimensions dimensions;
	private final Map<String, String> metadata;
	private final List<RoomBlock> blocks;
	private final List<RoomMarker> markers;

	public VirtualRoom(
			RoomId id,
			String displayName,
			RoomDimensions dimensions,
			Map<String, String> metadata,
			Collection<RoomBlock> blocks,
			Collection<RoomMarker> markers) {
		this.id = Objects.requireNonNull(id, "id");
		this.displayName = RoomValidation.text(displayName, "display name", 256);
		this.dimensions = Objects.requireNonNull(dimensions, "dimensions");

		TreeMap<String, String> sortedMetadata = new TreeMap<>();
		Objects.requireNonNull(metadata, "metadata").forEach((key, value) -> {
			RoomValidation.metadataKey(key);
			RoomValidation.text(value, "metadata value", 16_384);
			sortedMetadata.put(key, value);
		});
		if (sortedMetadata.size() > RoomValidation.MAX_METADATA_ENTRIES) {
			throw new IllegalArgumentException("Too many room metadata entries");
		}
		this.metadata = Collections.unmodifiableMap(sortedMetadata);

		TreeMap<RoomPosition, RoomBlock> blocksByPosition = new TreeMap<>();
		for (RoomBlock block : Objects.requireNonNull(blocks, "blocks")) {
			Objects.requireNonNull(block, "block");
			dimensions.requireContains(block.position());
			if (blocksByPosition.put(block.position(), block) != null) {
				throw new IllegalArgumentException("More than one block at " + block.position());
			}
		}
		if (blocksByPosition.size() > RoomValidation.MAX_BLOCKS) {
			throw new IllegalArgumentException("Room contains too many blocks");
		}
		this.blocks = List.copyOf(blocksByPosition.values());

		TreeMap<String, RoomMarker> markersById = new TreeMap<>();
		for (RoomMarker marker : Objects.requireNonNull(markers, "markers")) {
			Objects.requireNonNull(marker, "marker");
			dimensions.requireContains(marker.position());
			if (markersById.put(marker.id(), marker) != null) {
				throw new IllegalArgumentException("Duplicate room marker id: " + marker.id());
			}
		}
		if (markersById.size() > RoomValidation.MAX_MARKERS) {
			throw new IllegalArgumentException("Room contains too many markers");
		}
		this.markers = List.copyOf(markersById.values());
	}

	public static Builder builder(RoomId id, String displayName, RoomDimensions dimensions) {
		return new Builder(id, displayName, dimensions, null);
	}

	public static Builder builder(RoomId id, String displayName, RoomCoordinateSystem coordinates) {
		return new Builder(id, displayName, coordinates.dimensions(), coordinates);
	}

	public RoomId id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public RoomDimensions dimensions() {
		return dimensions;
	}

	public Map<String, String> metadata() {
		return metadata;
	}

	public List<RoomBlock> blocks() {
		return blocks;
	}

	public List<RoomMarker> markers() {
		return markers;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof VirtualRoom other)) {
			return false;
		}
		return id.equals(other.id)
				&& displayName.equals(other.displayName)
				&& dimensions.equals(other.dimensions)
				&& metadata.equals(other.metadata)
				&& blocks.equals(other.blocks)
				&& markers.equals(other.markers);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, displayName, dimensions, metadata, blocks, markers);
	}

	public static final class Builder {
		private final RoomId id;
		private final String displayName;
		private final RoomDimensions dimensions;
		private final RoomCoordinateSystem coordinates;
		private final Map<String, String> metadata = new TreeMap<>();
		private final List<RoomBlock> blocks = new ArrayList<>();
		private final List<RoomMarker> markers = new ArrayList<>();

		private Builder(
				RoomId id,
				String displayName,
				RoomDimensions dimensions,
				RoomCoordinateSystem coordinates) {
			this.id = Objects.requireNonNull(id, "id");
			this.displayName = displayName;
			this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
			this.coordinates = coordinates;
		}

		public Builder metadata(String key, String value) {
			metadata.put(key, value);
			return this;
		}

		public Builder block(RoomBlock block) {
			blocks.add(block);
			return this;
		}

		public Builder worldBlock(
				int worldX,
				int worldY,
				int worldZ,
				String blockId,
				Map<String, String> properties,
				byte[] blockEntityNbt) {
			if (coordinates == null) {
				throw new IllegalStateException("worldBlock requires a builder created with a RoomCoordinateSystem");
			}
			return block(new RoomBlock(
					coordinates.toLocal(worldX, worldY, worldZ),
					blockId,
					properties,
					blockEntityNbt));
		}

		public Builder marker(RoomMarker marker) {
			markers.add(marker);
			return this;
		}

		public VirtualRoom build() {
			return new VirtualRoom(id, displayName, dimensions, metadata, blocks, markers);
		}
	}
}
