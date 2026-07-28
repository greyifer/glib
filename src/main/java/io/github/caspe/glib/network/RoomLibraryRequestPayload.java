package io.github.caspe.glib.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record RoomLibraryRequestPayload() implements CustomPayload {
	public static final Id<RoomLibraryRequestPayload> ID =
		new Id<>(Identifier.of("glib", "room_library_request"));
	public static final PacketCodec<RegistryByteBuf, RoomLibraryRequestPayload> CODEC =
		PacketCodec.of((payload, buffer) -> {
		}, buffer -> new RoomLibraryRequestPayload());

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
