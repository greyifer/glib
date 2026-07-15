package io.github.caspe.glib.api.skin;

import java.util.Objects;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

@Environment(EnvType.CLIENT)
public record SkinLayer(SkinRegion region, NativeImage image, SkinBlendMode blendMode, float opacity) {
	public SkinLayer {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(image, "image");
		blendMode = blendMode == null ? SkinBlendMode.NORMAL : blendMode;
		opacity = Math.max(0.0F, Math.min(1.0F, opacity));
		if (image.getWidth() != region.width() || image.getHeight() != region.height()) {
			throw new IllegalArgumentException("Layer image must match region size");
		}
	}

	public static SkinLayer of(SkinRegion region, NativeImage image, SkinBlendMode blendMode) {
		return new SkinLayer(region, image, blendMode, 1.0F);
	}
}
