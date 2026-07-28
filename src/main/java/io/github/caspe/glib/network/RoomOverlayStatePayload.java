package io.github.caspe.glib.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import java.util.Objects;

public record RoomOverlayStatePayload(
		UUID sessionId,
		boolean active,
		BlockPos anchor,
		int width,
		int height,
		int depth) implements CustomPayload {
	public static final Id<RoomOverlayStatePayload> ID =
		new Id<>(Identifier.of("glib", "room_overlay_state"));
	public static final PacketCodec<RegistryByteBuf, RoomOverlayStatePayload> CODEC =
		PacketCodec.of(RoomOverlayStatePayload::write, RoomOverlayStatePayload::new);

	public RoomOverlayStatePayload {
		Objects.requireNonNull(sessionId, "sessionId");
		anchor = Objects.requireNonNull(anchor, "anchor").toImmutable();
		if (active) {
			if (width < 1 || width > 256
					|| height < 1 || height > 256
					|| depth < 1 || depth > 256
					|| (long) width * height * depth > 262_144L) {
				throw new IllegalArgumentException("Invalid active virtual-room overlay bounds");
			}
		} else if (width != 0 || height != 0 || depth != 0) {
			throw new IllegalArgumentException("Inactive virtual-room overlay must have empty bounds");
		}
	}

	private RoomOverlayStatePayload(RegistryByteBuf buffer) {
		this(
			buffer.readUuid(),
			buffer.readBoolean(),
			buffer.readBlockPos(),
			buffer.readVarInt(),
			buffer.readVarInt(),
			buffer.readVarInt());
	}

	private void write(RegistryByteBuf buffer) {
		buffer.writeUuid(sessionId);
		buffer.writeBoolean(active);
		buffer.writeBlockPos(anchor);
		buffer.writeVarInt(width);
		buffer.writeVarInt(height);
		buffer.writeVarInt(depth);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
