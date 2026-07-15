package io.github.caspe.glib.client;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.caspe.glib.api.skin.MutableSkinTexture;
import io.github.caspe.glib.api.skin.SkinModel;
import io.github.caspe.glib.api.skin.SkinOverrideRegistry;
import io.github.caspe.glib.api.skin.SkinSyncSnapshot;
import io.github.caspe.glib.network.SkinNetworking;
import io.github.caspe.glib.network.SkinSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.SkinTextures;

@Environment(EnvType.CLIENT)
public final class SkinSyncClient {
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final Map<UUID, MutableSkinTexture> SYNCED_TEXTURES = new ConcurrentHashMap<>();

	private SkinSyncClient() {
	}

	public static void registerReceiver() {
		if (REGISTERED.compareAndSet(false, true)) {
			SkinNetworking.registerPayloads();
			ClientPlayNetworking.registerGlobalReceiver(SkinSyncPayload.ID, (payload, context) -> apply(payload.snapshot()));
		}
	}

	public static void apply(SkinSyncSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		MutableSkinTexture current = SYNCED_TEXTURES.get(snapshot.uuid());
		if (current != null && current.width() == snapshot.width() && current.height() == snapshot.height()) {
			current.replaceNativePixels(snapshot.width(), snapshot.height(), snapshot.nativePixels());
			SkinOverrideRegistry.set(snapshot.uuid(), current, model(snapshot.model()));
			return;
		}

		NativeImage image = toImage(snapshot);
		MutableSkinTexture.create("synced/" + snapshot.uuid(), image).thenAccept(texture -> {
			MutableSkinTexture old = SYNCED_TEXTURES.put(snapshot.uuid(), texture);
			if (old != null) {
				old.close();
			}
			SkinOverrideRegistry.set(snapshot.uuid(), texture, model(snapshot.model()));
		}).whenComplete((texture, throwable) -> {
			if (throwable != null) {
				image.close();
			}
		});
	}

	public static void clear(UUID uuid) {
		MutableSkinTexture texture = SYNCED_TEXTURES.remove(uuid);
		if (texture != null) {
			texture.close();
		}
		SkinOverrideRegistry.clear(uuid);
	}

	public static void clearAll() {
		for (MutableSkinTexture texture : SYNCED_TEXTURES.values()) {
			texture.close();
		}
		SYNCED_TEXTURES.clear();
		SkinOverrideRegistry.clearAll();
	}

	private static NativeImage toImage(SkinSyncSnapshot snapshot) {
		NativeImage image = new NativeImage(snapshot.width(), snapshot.height(), false);
		int[] pixels = snapshot.nativePixels();
		for (int y = 0; y < snapshot.height(); y++) {
			for (int x = 0; x < snapshot.width(); x++) {
				image.setColor(x, y, pixels[y * snapshot.width() + x]);
			}
		}
		return image;
	}

	private static SkinTextures.Model model(SkinModel model) {
		return model == SkinModel.SLIM ? SkinTextures.Model.SLIM : SkinTextures.Model.WIDE;
	}
}
