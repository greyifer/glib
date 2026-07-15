package io.github.caspe.glib.api.skin;

import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class SkinRenderers {
	private SkinRenderers() {
	}

	public static void drawTexture(DrawContext context, Identifier texture, int x, int y, int width, int height) {
		context.drawTexture(texture, x, y, 0.0F, 0.0F, width, height, width, height);
	}

	public static void drawFace(DrawContext context, Identifier faceTexture, int x, int y, int size) {
		drawTexture(context, faceTexture, x, y, size, size);
	}

	public static void drawSkinRegion(DrawContext context, Identifier skinTexture, SkinRegion region, int x, int y, int width, int height) {
		context.drawTexture(skinTexture, x, y, width, height, (float) region.x(), (float) region.y(), region.width(), region.height(), 64, 64);
	}

	public static boolean drawPlayerFace(DrawContext context, GameProfile profile, FaceOptions options, int x, int y, int size) {
		CompletableFuture<Identifier> future = SkinService.faceTexture(profile, options);
		if (!future.isDone() || future.isCompletedExceptionally()) {
			return false;
		}
		drawFace(context, future.getNow(null), x, y, size);
		return true;
	}

	public static void drawTooltipFace(DrawContext context, Identifier faceTexture, int x, int y, int size) {
		drawFace(context, faceTexture, x, y, size);
	}
}
