package io.github.caspe.glib.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record TimerSyncPayload(String channel, String phase, String label, int remainingTicks, boolean visible) implements CustomPayload {
	public static final Id<TimerSyncPayload> ID = new Id<>(Identifier.of("glib", "timer_sync"));
	public static final PacketCodec<RegistryByteBuf, TimerSyncPayload> CODEC = PacketCodec.of(TimerSyncPayload::write, TimerSyncPayload::new);

	public TimerSyncPayload(RegistryByteBuf buf) {
		this(buf.readString(32), buf.readString(32), buf.readString(64), buf.readVarInt(), buf.readBoolean());
	}

	private void write(RegistryByteBuf buf) {
		buf.writeString(channel, 32);
		buf.writeString(phase, 32);
		buf.writeString(label, 64);
		buf.writeVarInt(remainingTicks);
		buf.writeBoolean(visible);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
