package io.github.caspe.glib.api.skin;

import java.util.Objects;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

@Environment(EnvType.CLIENT)
public final class SkinAnimations {
	private SkinAnimations() {
	}

	public static SkinAnimation blink(SkinRegion region, int closedArgb, int durationTicks) {
		Objects.requireNonNull(region, "region");
		return temporaryRegion(region, durationTicks, texture -> texture.fillRegionArgb(region, closedArgb));
	}

	public static SkinAnimation glow(SkinRegion region, int glowArgb, int durationTicks) {
		Objects.requireNonNull(region, "region");
		return temporaryRegion(region, durationTicks, texture -> texture.editRegion(region, image -> {
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					image.setColor(x, y, SkinColor.blendNative(SkinColor.argbToNative(glowArgb), image.getColor(x, y)));
				}
			}
		}));
	}

	public static SkinAnimation damageFlash(int flashArgb, int durationTicks) {
		return glow(SkinRegions.FULL, flashArgb, durationTicks);
	}

	public static SkinAnimation hueShift(SkinRegion region, float degreesPerTick, int durationTicks) {
		Objects.requireNonNull(region, "region");
		return (texture, ageTicks) -> {
			if (ageTicks >= durationTicks) {
				return false;
			}
			texture.editRegion(region, image -> {
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						int argb = SkinColor.nativeToArgb(image.getColor(x, y));
						image.setColor(x, y, SkinColor.argbToNative(SkinColor.hueShiftArgb(argb, degreesPerTick)));
					}
				}
			});
			return true;
		};
	}

	private static SkinAnimation temporaryRegion(SkinRegion region, int durationTicks, RegionEffect effect) {
		return new SkinAnimation() {
			private NativeImage original;

			@Override
			public boolean tick(MutableSkinTexture texture, int ageTicks) {
				if (ageTicks == 0) {
					original = texture.copyRegion(region);
					effect.apply(texture);
					return true;
				}
				if (ageTicks < durationTicks) {
					return true;
				}
				if (original != null) {
					try {
						texture.pasteRegion(region, original);
					} finally {
						original.close();
						original = null;
					}
				}
				return false;
			}
		};
	}

	@FunctionalInterface
	private interface RegionEffect {
		void apply(MutableSkinTexture texture);
	}
}
