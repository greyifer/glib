package io.github.caspe.glib.network;

import io.github.caspe.glib.api.skin.SkinModel;
import io.github.caspe.glib.api.skin.SkinSyncSnapshot;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SkinSyncPayload(SkinSyncSnapshot snapshot) implements CustomPayload {
	public static final Id<SkinSyncPayload> ID = new Id<>(Identifier.of("glib", "skin_sync"));
	public static final PacketCodec<RegistryByteBuf, SkinSyncPayload> CODEC = PacketCodec.of(SkinSyncPayload::write, SkinSyncPayload::new);
	private static final int MAX_SKIN_PIXELS = 128 * 128;

	public SkinSyncPayload(RegistryByteBuf buf) {
		this(new SkinSyncSnapshot(
			buf.readUuid(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readIntArray(MAX_SKIN_PIXELS),
			SkinModel.fromName(buf.readString(16))
		));
	}

	private void write(RegistryByteBuf buf) {
		buf.writeUuid(snapshot.uuid());
		buf.writeVarInt(snapshot.width());
		buf.writeVarInt(snapshot.height());
		buf.writeIntArray(snapshot.nativePixels());
		buf.writeString(snapshot.model().serializedName());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
