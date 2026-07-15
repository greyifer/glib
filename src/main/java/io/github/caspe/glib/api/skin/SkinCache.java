package io.github.caspe.glib.api.skin;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.client.ClientSkinService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class SkinCache {
	private SkinCache() {
	}

	public static void faceExpiry(Duration expiry) {
		ClientSkinService.faceExpiry(expiry);
	}

	public static Duration faceExpiry() {
		return ClientSkinService.faceExpiry();
	}

	public static void offlineFallback(boolean enabled) {
		ClientSkinService.offlineFallback(enabled);
	}

	public static boolean offlineFallback() {
		return ClientSkinService.offlineFallback();
	}

	public static CompletableFuture<Void> preloadFaces(Collection<GameProfile> profiles, FaceOptions options) {
		return ClientSkinService.preloadFaces(profiles, options);
	}

	public static void clearFace(UUID uuid) {
		ClientSkinService.clearFaceCache(uuid);
	}

	public static void clearFaceCache() {
		ClientSkinService.clearFaceCache();
	}

	public static void clearExpiredFaces() {
		ClientSkinService.clearExpiredFaces();
	}
}
