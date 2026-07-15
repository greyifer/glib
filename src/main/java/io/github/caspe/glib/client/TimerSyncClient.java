package io.github.caspe.glib.client;

import io.github.caspe.glib.api.timer.GlibTimers;
import io.github.caspe.glib.network.TimerSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

final class TimerSyncClient {
	private TimerSyncClient() {
	}

	static void registerReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(TimerSyncPayload.ID, (payload, context) ->
			context.client().execute(() -> {
				if (payload.visible()) {
					GlibTimers.show(payload.label(), payload.remainingTicks());
				} else {
					GlibTimers.hide();
				}
			})
		);
	}
}
