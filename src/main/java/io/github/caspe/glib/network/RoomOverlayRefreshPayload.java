package io.github.caspe.glib.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;
import java.util.Objects;

public record RoomOverlayRefreshPayload(
		UUID sessionId,
		int chunkX,
		int chunkZ) implements CustomPayload {
	public static final Id<RoomOverlayRefreshPayload> ID =
		new Id<>(Identifier.of("glib", "room_overlay_refresh"));
	public static final PacketCodec<RegistryByteBuf, RoomOverlayRefreshPayload> CODEC =
		PacketCodec.of(RoomOverlayRefreshPayload::write, RoomOverlayRefreshPayload::new);

	public RoomOverlayRefreshPayload {
		Objects.requireNonNull(sessionId, "sessionId");
	}

	private RoomOverlayRefreshPayload(RegistryByteBuf buffer) {
		this(buffer.readUuid(), buffer.readInt(), buffer.readInt());
	}

	private void write(RegistryByteBuf buffer) {
		buffer.writeUuid(sessionId);
		buffer.writeInt(chunkX);
		buffer.writeInt(chunkZ);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
