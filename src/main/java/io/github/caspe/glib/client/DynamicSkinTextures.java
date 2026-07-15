package io.github.caspe.glib.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.caspe.glib.api.skin.SkinAnimator;
import io.github.caspe.glib.api.skin.MutableSkinTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

@Environment(EnvType.CLIENT)
public final class DynamicSkinTextures {
	private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
	private static final Set<MutableSkinTexture> DIRTY_TEXTURES = ConcurrentHashMap.newKeySet();

	private DynamicSkinTextures() {
	}

	public static void init() {
		if (INITIALIZED.compareAndSet(false, true)) {
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				SkinAnimator.tickAll();
				flushDirtyTextures();
			});
		}
	}

	public static void markDirty(MutableSkinTexture texture) {
		if (!texture.isClosed()) {
			DIRTY_TEXTURES.add(texture);
		}
	}

	public static void unregister(MutableSkinTexture texture) {
		DIRTY_TEXTURES.remove(texture);
	}

	private static void flushDirtyTextures() {
		for (MutableSkinTexture texture : DIRTY_TEXTURES) {
			if (DIRTY_TEXTURES.remove(texture) && !texture.isClosed()) {
				texture.uploadOnClientThread();
			}
		}
	}
}
