package io.github.caspe.glib.api.skin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.client.ClientSkinService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class SkinService {
	private SkinService() {
	}

	public static SkinTextures getTextures(GameProfile profile) {
		return ClientSkinService.getTextures(profile);
	}

	public static SkinTextures getTextures(AbstractClientPlayerEntity player) {
		Objects.requireNonNull(player, "player");
		return player.getSkinTextures();
	}

	public static CompletableFuture<SkinTextures> fetchTextures(GameProfile profile) {
		return ClientSkinService.fetchTextures(profile);
	}

	public static CompletableFuture<SkinTextures> fetchTextures(UUID uuid, @Nullable String name) {
		return fetchTextures(new GameProfile(uuid, name == null ? "" : name));
	}

	public static CompletableFuture<NativeImage> downloadSkin(GameProfile profile) {
		return ClientSkinService.downloadSkin(profile);
	}

	public static CompletableFuture<NativeImage> downloadSkin(SkinTextures textures) {
		return ClientSkinService.downloadSkin(textures);
	}

	public static CompletableFuture<MutableSkinTexture> createMutableSkin(GameProfile profile, String prefix) {
		return ClientSkinService.createMutableSkin(profile, prefix);
	}

	public static CompletableFuture<MutableSkinTexture> createMutableSkin(AbstractClientPlayerEntity player, String prefix) {
		Objects.requireNonNull(player, "player");
		return createMutableSkin(player.getGameProfile(), prefix);
	}

	public static CompletableFuture<MutableSkinTexture> createMutableFace(GameProfile profile, String prefix) {
		return createMutableFace(profile, FaceOptions.DEFAULT, prefix);
	}

	public static CompletableFuture<MutableSkinTexture> createMutableFace(GameProfile profile, FaceOptions options, String prefix) {
		return ClientSkinService.createMutableFace(profile, options, prefix);
	}

	public static CompletableFuture<Identifier> faceTexture(GameProfile profile) {
		return faceTexture(profile, FaceOptions.DEFAULT);
	}

	public static CompletableFuture<Identifier> faceTexture(GameProfile profile, FaceOptions options) {
		return ClientSkinService.faceTexture(profile, options);
	}

	public static CompletableFuture<Identifier> faceTexture(AbstractClientPlayerEntity player) {
		Objects.requireNonNull(player, "player");
		return faceTexture(player.getGameProfile(), FaceOptions.DEFAULT);
	}

	public static void clearFaceTextureCache() {
		ClientSkinService.clearFaceCache();
	}
}
