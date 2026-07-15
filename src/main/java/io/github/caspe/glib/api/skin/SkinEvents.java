package io.github.caspe.glib.api.skin;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.SkinTextures;

@Environment(EnvType.CLIENT)
public final class SkinEvents {
	private static final CopyOnWriteArrayList<Loaded> LOADED = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<Changed> CHANGED = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<OverrideApplied> OVERRIDE_APPLIED = new CopyOnWriteArrayList<>();

	private SkinEvents() {
	}

	public static Subscription onSkinLoaded(Loaded listener) {
		LOADED.add(listener);
		return () -> LOADED.remove(listener);
	}

	public static Subscription onSkinChanged(Changed listener) {
		CHANGED.add(listener);
		return () -> CHANGED.remove(listener);
	}

	public static Subscription onOverrideApplied(OverrideApplied listener) {
		OVERRIDE_APPLIED.add(listener);
		return () -> OVERRIDE_APPLIED.remove(listener);
	}

	public static void fireLoaded(GameProfile profile, SkinTextures textures) {
		for (Loaded listener : LOADED) {
			listener.onSkinLoaded(profile, textures);
		}
	}

	public static void fireChanged(MutableSkinTexture texture) {
		for (Changed listener : CHANGED) {
			listener.onSkinChanged(texture);
		}
	}

	public static void fireOverrideApplied(UUID uuid, SkinOverride override) {
		for (OverrideApplied listener : OVERRIDE_APPLIED) {
			listener.onOverrideApplied(uuid, override);
		}
	}

	@FunctionalInterface
	public interface Loaded {
		void onSkinLoaded(GameProfile profile, SkinTextures textures);
	}

	@FunctionalInterface
	public interface Changed {
		void onSkinChanged(MutableSkinTexture texture);
	}

	@FunctionalInterface
	public interface OverrideApplied {
		void onOverrideApplied(UUID uuid, SkinOverride override);
	}

	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		@Override
		void close();
	}
}
