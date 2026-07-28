package io.github.caspe.glib.network;

import io.github.caspe.glib.api.room.RoomId;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record RoomLibrarySyncPayload(
		long revision,
		int pageIndex,
		int pageCount,
		int totalRooms,
		boolean truncated,
		List<Entry> rooms) implements CustomPayload {
	public static final Id<RoomLibrarySyncPayload> ID =
		new Id<>(Identifier.of("glib", "room_library_sync"));
	public static final PacketCodec<RegistryByteBuf, RoomLibrarySyncPayload> CODEC =
		PacketCodec.of(RoomLibrarySyncPayload::write, RoomLibrarySyncPayload::new);
	public static final int MAX_ENTRIES_PER_PAGE = 512;
	public static final int MAX_PAGES = 32;

	public RoomLibrarySyncPayload {
		rooms = List.copyOf(rooms);
		if (pageCount < 1 || pageCount > MAX_PAGES
				|| pageIndex < 0 || pageIndex >= pageCount
				|| totalRooms < rooms.size()
				|| rooms.size() > MAX_ENTRIES_PER_PAGE) {
			throw new IllegalArgumentException("Invalid virtual-room summary page");
		}
	}

	private RoomLibrarySyncPayload(RegistryByteBuf buffer) {
		this(
			buffer.readVarLong(),
			buffer.readVarInt(),
			buffer.readVarInt(),
			buffer.readVarInt(),
			buffer.readBoolean(),
			readEntries(buffer));
	}

	private void write(RegistryByteBuf buffer) {
		buffer.writeVarLong(revision);
		buffer.writeVarInt(pageIndex);
		buffer.writeVarInt(pageCount);
		buffer.writeVarInt(totalRooms);
		buffer.writeBoolean(truncated);
		buffer.writeVarInt(rooms.size());
		for (Entry entry : rooms) {
			buffer.writeString(entry.id(), 64);
			buffer.writeString(entry.name(), 1024);
			buffer.writeVarInt(entry.width());
			buffer.writeVarInt(entry.height());
			buffer.writeVarInt(entry.depth());
			buffer.writeVarInt(entry.blockCount());
			buffer.writeVarInt(entry.markerCount());
		}
	}

	private static List<Entry> readEntries(RegistryByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 0 || count > MAX_ENTRIES_PER_PAGE) {
			throw new IllegalArgumentException("Invalid virtual-room summary count: " + count);
		}
		List<Entry> entries = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			entries.add(new Entry(
				buffer.readString(64),
				buffer.readString(1024),
				buffer.readVarInt(),
				buffer.readVarInt(),
				buffer.readVarInt(),
				buffer.readVarInt(),
				buffer.readVarInt()));
		}
		return List.copyOf(entries);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}

	public record Entry(
			String id,
			String name,
			int width,
			int height,
			int depth,
			int blockCount,
			int markerCount) {
		public Entry {
			id = RoomId.of(id).value();
			Objects.requireNonNull(name, "name");
			if (name.length() > 256 || name.indexOf('\0') >= 0) {
				throw new IllegalArgumentException("Invalid virtual-room display name");
			}
			if (width < 1 || width > 256
					|| height < 1 || height > 256
					|| depth < 1 || depth > 256
					|| (long) width * height * depth > 262_144L
					|| blockCount < 0 || blockCount > 2_000_000
					|| markerCount < 0 || markerCount > 16_384) {
				throw new IllegalArgumentException("Invalid virtual-room summary values");
			}
		}
	}
}
