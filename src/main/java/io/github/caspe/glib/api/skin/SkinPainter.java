package io.github.caspe.glib.api.skin;

import java.util.Objects;

public final class SkinPainter {
	private SkinPainter() {
	}

	public static MutableSkinTexture paint(MutableSkinTexture texture, SkinPaintTarget target, SkinBrush brush, int argb) {
		Objects.requireNonNull(texture, "texture");
		Objects.requireNonNull(target, "target");
		SkinBrush activeBrush = brush == null ? SkinBrush.DEFAULT : brush;
		return paintRegion(texture, target.region(), target.localX(), target.localY(), activeBrush, argb);
	}

	public static MutableSkinTexture paintAt(MutableSkinTexture texture, int centerX, int centerY, SkinBrush brush, int argb) {
		Objects.requireNonNull(texture, "texture");
		SkinBrush activeBrush = brush == null ? SkinBrush.DEFAULT : brush;
		int radius = activeBrush.radius();
		int radiusSquared = radius * radius;

		texture.edit(image -> {
			for (int y = Math.max(0, centerY - radius); y <= Math.min(image.getHeight() - 1, centerY + radius); y++) {
				for (int x = Math.max(0, centerX - radius); x <= Math.min(image.getWidth() - 1, centerX + radius); x++) {
					int dx = x - centerX;
					int dy = y - centerY;
					int distanceSquared = dx * dx + dy * dy;
					if (distanceSquared > radiusSquared) {
						continue;
					}

					paintPixel(image, x, y, activeBrush, argb, dx, dy, distanceSquared, radiusSquared);
				}
			}
		});
		return texture;
	}

	private static MutableSkinTexture paintRegion(MutableSkinTexture texture, SkinRegion region, int centerX, int centerY, SkinBrush brush, int argb) {
		int radius = brush.radius();
		int radiusSquared = radius * radius;

		texture.edit(image -> {
			int minX = Math.max(0, centerX - radius);
			int maxX = Math.min(region.width() - 1, centerX + radius);
			int minY = Math.max(0, centerY - radius);
			int maxY = Math.min(region.height() - 1, centerY + radius);

			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					int dx = x - centerX;
					int dy = y - centerY;
					int distanceSquared = dx * dx + dy * dy;
					if (distanceSquared > radiusSquared) {
						continue;
					}

					paintPixel(image, region.x() + x, region.y() + y, brush, argb, dx, dy, distanceSquared, radiusSquared);
				}
			}
		});
		return texture;
	}

	public static int sample(MutableSkinTexture texture, SkinPaintTarget target) {
		Objects.requireNonNull(texture, "texture");
		Objects.requireNonNull(target, "target");
		return texture.getPixelArgb(target.textureX(), target.textureY());
	}

	private static int materialize(int argb, SkinBrush brush, int dx, int dy, int distanceSquared, int radiusSquared) {
		float roughness = brush.roughness();
		float metallic = brush.metallic();
		float gloss = 1.0F - roughness;
		float radius = Math.max(1.0F, (float) Math.sqrt(Math.max(1, radiusSquared)));
		float normalX = dx / radius;
		float normalY = dy / radius;
		float normalZ = (float) Math.sqrt(Math.max(0.0F, 1.0F - normalX * normalX - normalY * normalY));
		float light = clamp01(normalX * -0.46F + normalY * -0.62F + normalZ * 0.64F);
		float sharpHighlight = (float) Math.pow(light, 4.0F + gloss * 92.0F);
		float broadHighlight = (float) Math.pow(light, 1.2F + roughness * 5.0F);
		float edgeShadow = (1.0F - normalZ) * metallic * (0.22F + roughness * 0.16F);
		float specular = metallic * (sharpHighlight * (0.25F + gloss * 1.75F) + broadHighlight * roughness * 0.34F);
		float diffuse = 1.0F - roughness * 0.10F - metallic * 0.12F - edgeShadow + broadHighlight * metallic * roughness * 0.08F;
		float grain = (noise(dx, dy, distanceSquared) - 0.5F) * roughness * (1.0F - metallic * 0.45F) * 0.10F;
		float desaturate = roughness * (1.0F - metallic * 0.35F) * 0.22F;

		int red = (argb >>> 16) & 0xFF;
		int green = (argb >>> 8) & 0xFF;
		int blue = argb & 0xFF;
		int gray = Math.round(red * 0.30F + green * 0.59F + blue * 0.11F);
		red = materialChannel(red, gray, diffuse + grain, specular, desaturate);
		green = materialChannel(green, gray, diffuse + grain, specular, desaturate);
		blue = materialChannel(blue, gray, diffuse + grain, specular, desaturate);
		return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
	}

	private static void paintPixel(net.minecraft.client.texture.NativeImage image, int x, int y, SkinBrush brush, int argb, int dx, int dy, int distanceSquared, int radiusSquared) {
		int radius = brush.radius();
		float edge = radius <= 1 ? 1.0F : 1.0F - Math.max(0.0F, (float) Math.sqrt(distanceSquared) - radius + 1.0F);
		float opacity = brush.opacity() * Math.max(0.35F, Math.min(1.0F, edge));
		int destinationArgb = SkinColor.nativeToArgb(image.getColor(x, y));
		if (SkinColor.alpha(destinationArgb) == 0) {
			return;
		}

		int paintArgb = materialize(argb, brush, dx, dy, distanceSquared, radiusSquared);
		image.setColor(x, y, SkinColor.argbToNative(brush.blendMode().blend(paintArgb, destinationArgb, opacity)));
	}

	private static int materialChannel(int value, int gray, float diffuse, float specular, float desaturate) {
		float base = value + (gray - value) * desaturate;
		float shaded = base * diffuse + (255.0F - base) * specular;
		return Math.max(0, Math.min(255, Math.round(shaded)));
	}

	private static float noise(int x, int y, int salt) {
		int hash = x * 73428767 ^ y * 912367 ^ salt * 42349;
		hash ^= hash >>> 13;
		hash *= 1274126177;
		hash ^= hash >>> 16;
		return (hash & 0xFF) / 255.0F;
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
