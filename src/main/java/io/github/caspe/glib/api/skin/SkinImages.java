package io.github.caspe.glib.api.skin;

import java.util.Objects;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

@Environment(EnvType.CLIENT)
public final class SkinImages {
	private static final int FACE_X = 8;
	private static final int FACE_Y = 8;
	private static final int HAT_X = 40;
	private static final int HAT_Y = 8;
	private static final int FACE_SIZE = 8;

	private SkinImages() {
	}

	public static NativeImage copy(NativeImage source) {
		Objects.requireNonNull(source, "source");
		NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), false);
		copy.copyFrom(source);
		return copy;
	}

	public static NativeImage copyFace(NativeImage skin) {
		return copyFace(skin, FaceOptions.DEFAULT);
	}

	public static NativeImage copyFace(NativeImage skin, FaceOptions options) {
		Objects.requireNonNull(skin, "skin");
		Objects.requireNonNull(options, "options");
		requireSkinSize(skin);

		NativeImage face = new NativeImage(FACE_SIZE, FACE_SIZE, false);
		for (int y = 0; y < FACE_SIZE; y++) {
			for (int x = 0; x < FACE_SIZE; x++) {
				int color = skin.getColor(FACE_X + x, FACE_Y + y);
				if (options.includeHatLayer()) {
					color = SkinColor.blendNative(skin.getColor(HAT_X + x, HAT_Y + y), color);
				}
				face.setColor(x, y, color);
			}
		}

		if (options.size() == FACE_SIZE) {
			return face;
		}

		NativeImage resized = scaleNearest(face, options.size());
		face.close();
		return resized;
	}

	public static NativeImage copyRegion(NativeImage source, int x, int y, int width, int height) {
		Objects.requireNonNull(source, "source");
		NativeImage region = new NativeImage(width, height, false);
		for (int destY = 0; destY < height; destY++) {
			for (int destX = 0; destX < width; destX++) {
				region.setColor(destX, destY, source.getColor(x + destX, y + destY));
			}
		}
		return region;
	}

	public static NativeImage copyRegion(NativeImage source, SkinRegion region) {
		Objects.requireNonNull(region, "region");
		return copyRegion(source, region.x(), region.y(), region.width(), region.height());
	}

	private static NativeImage scaleNearest(NativeImage source, int size) {
		NativeImage resized = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				resized.setColor(x, y, source.getColor(x * FACE_SIZE / size, y * FACE_SIZE / size));
			}
		}
		return resized;
	}

	private static void requireSkinSize(NativeImage skin) {
		if (skin.getWidth() < 64 || skin.getHeight() < 64) {
			throw new IllegalArgumentException("Expected a 64x64 or larger Minecraft skin, got " + skin.getWidth() + "x" + skin.getHeight());
		}
	}
}
