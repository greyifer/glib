package io.github.caspe.glib.client;

import io.github.caspe.glib.api.client.GlibKeybindHud;
import io.github.caspe.glib.api.client.menu.GlibMenuKeybind;
import io.github.caspe.glib.api.client.menu.GlibMenuRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public final class GlibClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		GlibMenuKeybind.register();
		ClientTickEvents.END_CLIENT_TICK.register(GlibMenuKeybind::handle);
		registerMenuEntries();
		DynamicSkinTextures.init();
		SkinSyncClient.registerReceiver();
		TimerSyncClient.registerReceiver();
		ConfigSyncClient.registerReceiver();
		HudRenderCallback.EVENT.register(GlibTimerHud::render);
		HudRenderCallback.EVENT.register(GlibHudListRenderer::render);
		HudRenderCallback.EVENT.register(GlibKeybindHud::render);
	}

	private void registerMenuEntries() {
		GlibMenuRegistry.loadContributors();
	}
}
