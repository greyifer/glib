package io.github.caspe.glib.api.skin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.SkinTextures;

@Environment(EnvType.CLIENT)
public final class SkinOverrideRegistry {
	private static final Map<UUID, SkinOverride> OVERRIDES = new ConcurrentHashMap<>();

	private SkinOverrideRegistry() {
	}

	public static void set(UUID uuid, MutableSkinTexture texture, SkinTextures baseTextures) {
		set(uuid, SkinOverride.replacingTexture(baseTextures, texture.id()));
	}

	public static void set(UUID uuid, MutableSkinTexture texture, SkinTextures.Model model) {
		set(uuid, SkinOverride.of(texture.id(), model));
	}

	public static void set(UUID uuid, SkinOverride override) {
		OVERRIDES.put(uuid, override);
		SkinEvents.fireOverrideApplied(uuid, override);
	}

	public static Optional<SkinOverride> get(UUID uuid) {
		return SkinCompatibilityHooks.resolveOverride(uuid, Optional.ofNullable(OVERRIDES.get(uuid)));
	}

	public static Optional<SkinTextures> getTextures(UUID uuid) {
		return get(uuid).map(override -> override
			.withTexture(SkinCompatibilityHooks.resolveTexture(uuid, override.texture()))
			.toSkinTextures());
	}

	public static boolean has(UUID uuid) {
		return OVERRIDES.containsKey(uuid);
	}

	public static void clear(UUID uuid) {
		OVERRIDES.remove(uuid);
	}

	public static void clearAll() {
		OVERRIDES.clear();
	}

	public static Map<UUID, SkinOverride> snapshot() {
		return Map.copyOf(OVERRIDES);
	}
}
