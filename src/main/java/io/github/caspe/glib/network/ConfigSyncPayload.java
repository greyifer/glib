package io.github.caspe.glib.network;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ConfigSyncPayload(String namespace, Map<String, String> values) implements CustomPayload {
	private static final int MAX_VALUES = 128;
	public static final Id<ConfigSyncPayload> ID = new Id<>(Identifier.of("glib", "config_sync"));
	public static final PacketCodec<RegistryByteBuf, ConfigSyncPayload> CODEC = PacketCodec.of(ConfigSyncPayload::write, ConfigSyncPayload::new);

	public ConfigSyncPayload(RegistryByteBuf buf) {
		this(buf.readString(64), readValues(buf));
	}

	private void write(RegistryByteBuf buf) {
		buf.writeString(namespace, 64);
		buf.writeVarInt(Math.min(values.size(), MAX_VALUES));
		int written = 0;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (written >= MAX_VALUES) {
				break;
			}
			buf.writeString(entry.getKey(), 64);
			buf.writeString(entry.getValue(), 256);
			written++;
		}
	}

	private static Map<String, String> readValues(RegistryByteBuf buf) {
		int count = Math.min(buf.readVarInt(), MAX_VALUES);
		Map<String, String> values = new HashMap<>();
		for (int i = 0; i < count; i++) {
			values.put(buf.readString(64), buf.readString(256));
		}
		return Map.copyOf(values);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
