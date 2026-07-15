package io.github.caspe.glib.client;

import io.github.caspe.glib.api.config.GlibSyncedConfig;
import io.github.caspe.glib.network.ConfigSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

final class ConfigSyncClient {
	private ConfigSyncClient() {
	}

	static void registerReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) ->
			context.client().execute(() -> GlibSyncedConfig.applyClientValues(payload.namespace(), payload.values()))
		);
	}
}
