package io.github.caspe.glib.api.request;

import java.util.Map;

import io.github.caspe.glib.network.GlibRequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public final class GlibClientRequests {
	private GlibClientRequests() {
	}

	public static boolean send(String namespace, String action) {
		return send(namespace, action, Map.of());
	}

	public static boolean send(String namespace, String action, Map<String, String> values) {
		if (!ClientPlayNetworking.canSend(GlibRequestPayload.ID)) {
			return false;
		}
		ClientPlayNetworking.send(new GlibRequestPayload(namespace, action, values));
		return true;
	}
}
