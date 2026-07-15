package io.github.caspe.glib.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.api.skin.FaceOptions;
import io.github.caspe.glib.api.skin.MutableSkinTexture;
import io.github.caspe.glib.api.skin.SkinEvents;
import io.github.caspe.glib.api.skin.SkinImages;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class ClientSkinService {
	private static final Map<FaceKey, CompletableFuture<MutableSkinTexture>> FACE_TEXTURES = new ConcurrentHashMap<>();
	private static final Map<FaceKey, Long> FACE_TEXTURE_TIMES = new ConcurrentHashMap<>();
	private static volatile Duration faceExpiry = Duration.ofMinutes(10);
	private static volatile boolean offlineFallback = true;

	private ClientSkinService() {
	}

	public static SkinTextures getTextures(GameProfile profile) {
		Objects.requireNonNull(profile, "profile");
		return MinecraftClient.getInstance().getSkinProvider().getSkinTextures(profile);
	}

	public static CompletableFuture<SkinTextures> fetchTextures(GameProfile profile) {
		Objects.requireNonNull(profile, "profile");
		return ClientTasks.supply(() -> MinecraftClient.getInstance().getSkinProvider().fetchSkinTextures(profile))
			.thenCompose(future -> future)
			.thenApply(textures -> {
				SkinEvents.fireLoaded(profile, textures);
				return textures;
			});
	}

	public static CompletableFuture<NativeImage> downloadSkin(GameProfile profile) {
		Objects.requireNonNull(profile, "profile");
		return fetchTextures(profile)
			.thenCompose(textures -> downloadSkin(textures).exceptionallyCompose(throwable -> {
				if (!offlineFallback) {
					return CompletableFuture.failedFuture(throwable);
				}
				return downloadSkin(getTextures(profile));
			}));
	}

	public static CompletableFuture<NativeImage> downloadSkin(SkinTextures textures) {
		Objects.requireNonNull(textures, "textures");
		String url = textures.textureUrl();

		if (url != null && !url.isBlank()) {
			return CompletableFuture.supplyAsync(() -> readUrl(url));
		}

		return ClientTasks.supply(() -> readResourceTexture(textures.texture()));
	}

	public static CompletableFuture<MutableSkinTexture> createMutableSkin(GameProfile profile, String prefix) {
		return downloadSkin(profile).thenCompose(image -> {
			CompletableFuture<MutableSkinTexture> future = MutableSkinTexture.create(prefix, image);
			future.whenComplete((texture, throwable) -> {
				if (throwable != null) {
					image.close();
				}
			});
			return future;
		});
	}

	public static CompletableFuture<MutableSkinTexture> createMutableFace(GameProfile profile, FaceOptions options, String prefix) {
		Objects.requireNonNull(options, "options");
		return downloadSkin(profile).thenCompose(image -> {
			NativeImage face = null;
			try {
				face = SkinImages.copyFace(image, options);
			} finally {
				image.close();
			}

			NativeImage capturedFace = face;
			CompletableFuture<MutableSkinTexture> future = MutableSkinTexture.create(prefix, capturedFace);
			future.whenComplete((texture, throwable) -> {
				if (throwable != null) {
					capturedFace.close();
				}
			});
			return future;
		});
	}

	public static CompletableFuture<Identifier> faceTexture(GameProfile profile, FaceOptions options) {
		Objects.requireNonNull(profile, "profile");
		Objects.requireNonNull(options, "options");
		FaceKey key = FaceKey.of(profile, options);
		clearExpiredFace(key);
		return FACE_TEXTURES.computeIfAbsent(key, unused -> {
				FACE_TEXTURE_TIMES.put(key, System.currentTimeMillis());
				CompletableFuture<MutableSkinTexture> future = createMutableFace(profile, options, "face_" + key.safeName());
				future.whenComplete((texture, throwable) -> {
					if (throwable != null) {
						FACE_TEXTURES.remove(key, future);
						FACE_TEXTURE_TIMES.remove(key);
					}
				});
				return future;
			})
			.thenApply(MutableSkinTexture::id);
	}

	public static CompletableFuture<Void> preloadFaces(Collection<GameProfile> profiles, FaceOptions options) {
		Objects.requireNonNull(profiles, "profiles");
		Objects.requireNonNull(options, "options");
		CompletableFuture<?>[] futures = profiles.stream()
			.map(profile -> faceTexture(profile, options))
			.toArray(CompletableFuture[]::new);
		return CompletableFuture.allOf(futures);
	}

	public static void clearFaceCache() {
		for (CompletableFuture<MutableSkinTexture> future : FACE_TEXTURES.values()) {
			future.thenAccept(MutableSkinTexture::close);
		}
		FACE_TEXTURES.clear();
		FACE_TEXTURE_TIMES.clear();
	}

	public static void clearFaceCache(UUID uuid) {
		Objects.requireNonNull(uuid, "uuid");
		String id = uuid.toString();
		for (FaceKey key : FACE_TEXTURES.keySet()) {
			if (id.equals(key.id())) {
				clearFace(key);
			}
		}
	}

	public static void clearExpiredFaces() {
		for (FaceKey key : FACE_TEXTURES.keySet()) {
			clearExpiredFace(key);
		}
	}

	public static void faceExpiry(Duration expiry) {
		Objects.requireNonNull(expiry, "expiry");
		if (expiry.isNegative() || expiry.isZero()) {
			throw new IllegalArgumentException("expiry must be positive");
		}
		faceExpiry = expiry;
		clearExpiredFaces();
	}

	public static Duration faceExpiry() {
		return faceExpiry;
	}

	public static void offlineFallback(boolean enabled) {
		offlineFallback = enabled;
	}

	public static boolean offlineFallback() {
		return offlineFallback;
	}

	private static void clearExpiredFace(FaceKey key) {
		Long loadedAt = FACE_TEXTURE_TIMES.get(key);
		if (loadedAt != null && System.currentTimeMillis() - loadedAt >= faceExpiry.toMillis()) {
			clearFace(key);
		}
	}

	private static void clearFace(FaceKey key) {
		CompletableFuture<MutableSkinTexture> future = FACE_TEXTURES.remove(key);
		FACE_TEXTURE_TIMES.remove(key);
		if (future != null) {
			future.thenAccept(MutableSkinTexture::close);
		}
	}

	private static NativeImage readUrl(String url) {
		try {
			HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(10_000);
			connection.setRequestProperty("User-Agent", "glib-skin-library");

			try (InputStream input = connection.getInputStream()) {
				return NativeImage.read(input);
			}
		} catch (IOException exception) {
			throw new CompletionException(exception);
		}
	}

	private static NativeImage readResourceTexture(Identifier texture) {
		try {
			Resource resource = MinecraftClient.getInstance().getResourceManager().getResourceOrThrow(texture);
			try (InputStream input = resource.getInputStream()) {
				return NativeImage.read(input);
			}
		} catch (IOException exception) {
			throw new CompletionException(exception);
		}
	}

	private record FaceKey(String id, FaceOptions options) {
		static FaceKey of(GameProfile profile, FaceOptions options) {
			String id = profile.getId() != null ? profile.getId().toString() : profile.getName();
			return new FaceKey(id == null ? "unknown" : id, options);
		}

		String safeName() {
			return id.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
		}
	}
}
