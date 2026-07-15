package io.github.caspe.glib.api.skin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class SkinCompatibilityHooks {
	private static final CopyOnWriteArrayList<OverrideResolver> OVERRIDE_RESOLVERS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<TextureResolver> TEXTURE_RESOLVERS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<SkinTexturesHook> THIRD_PERSON_HOOKS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<SkinTexturesHook> COSMETIC_HOOKS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<SkinTexturesHook> ARMOR_HOOKS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<CosmeticVisibilityPredicate> COSMETIC_VISIBILITY_PREDICATES = new CopyOnWriteArrayList<>();

	private SkinCompatibilityHooks() {
	}

	public static SkinEvents.Subscription registerOverrideResolver(OverrideResolver resolver) {
		OVERRIDE_RESOLVERS.add(resolver);
		return () -> OVERRIDE_RESOLVERS.remove(resolver);
	}

	public static SkinEvents.Subscription registerTextureResolver(TextureResolver resolver) {
		TEXTURE_RESOLVERS.add(resolver);
		return () -> TEXTURE_RESOLVERS.remove(resolver);
	}

	public static SkinEvents.Subscription registerThirdPersonHook(SkinTexturesHook hook) {
		THIRD_PERSON_HOOKS.add(hook);
		return () -> THIRD_PERSON_HOOKS.remove(hook);
	}

	public static SkinEvents.Subscription registerCosmeticHook(SkinTexturesHook hook) {
		COSMETIC_HOOKS.add(hook);
		return () -> COSMETIC_HOOKS.remove(hook);
	}

	public static SkinEvents.Subscription registerArmorHook(SkinTexturesHook hook) {
		ARMOR_HOOKS.add(hook);
		return () -> ARMOR_HOOKS.remove(hook);
	}

	public static SkinEvents.Subscription registerCosmeticVisibilityPredicate(CosmeticVisibilityPredicate predicate) {
		COSMETIC_VISIBILITY_PREDICATES.add(predicate);
		return () -> COSMETIC_VISIBILITY_PREDICATES.remove(predicate);
	}

	public static Optional<SkinOverride> resolveOverride(UUID uuid, Optional<SkinOverride> current) {
		Optional<SkinOverride> resolved = current;
		for (OverrideResolver resolver : OVERRIDE_RESOLVERS) {
			resolved = resolver.resolve(uuid, resolved);
		}
		return resolved;
	}

	public static Identifier resolveTexture(UUID uuid, Identifier current) {
		Identifier resolved = current;
		for (TextureResolver resolver : TEXTURE_RESOLVERS) {
			resolved = resolver.resolve(uuid, resolved);
		}
		return resolved;
	}

	public static void fireThirdPerson(UUID uuid, SkinTextures textures) {
		fire(THIRD_PERSON_HOOKS, uuid, textures);
	}

	public static void fireCosmetic(UUID uuid, SkinTextures textures) {
		fire(COSMETIC_HOOKS, uuid, textures);
	}

	public static void fireArmor(UUID uuid, SkinTextures textures) {
		fire(ARMOR_HOOKS, uuid, textures);
	}

	public static boolean shouldHideCosmetics(UUID uuid) {
		for (CosmeticVisibilityPredicate predicate : COSMETIC_VISIBILITY_PREDICATES) {
			if (predicate.shouldHide(uuid)) {
				return true;
			}
		}
		return false;
	}

	private static void fire(CopyOnWriteArrayList<SkinTexturesHook> hooks, UUID uuid, SkinTextures textures) {
		for (SkinTexturesHook hook : hooks) {
			hook.accept(uuid, textures);
		}
	}

	@FunctionalInterface
	public interface OverrideResolver {
		Optional<SkinOverride> resolve(UUID uuid, Optional<SkinOverride> current);
	}

	@FunctionalInterface
	public interface TextureResolver {
		Identifier resolve(UUID uuid, Identifier current);
	}

	@FunctionalInterface
	public interface SkinTexturesHook {
		void accept(UUID uuid, SkinTextures textures);
	}

	@FunctionalInterface
	public interface CosmeticVisibilityPredicate {
		boolean shouldHide(UUID uuid);
	}
}
