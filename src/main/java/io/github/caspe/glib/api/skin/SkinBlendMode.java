package io.github.caspe.glib.api.skin;

public enum SkinBlendMode {
	NORMAL {
		@Override
		protected int blendOpaqueChannel(int source, int destination) {
			return source;
		}
	},
	MULTIPLY {
		@Override
		protected int blendOpaqueChannel(int source, int destination) {
			return source * destination / 255;
		}
	},
	SCREEN {
		@Override
		protected int blendOpaqueChannel(int source, int destination) {
			return 255 - (255 - source) * (255 - destination) / 255;
		}
	},
	OVERLAY {
		@Override
		protected int blendOpaqueChannel(int source, int destination) {
			if (destination < 128) {
				return 2 * source * destination / 255;
			}
			return 255 - 2 * (255 - source) * (255 - destination) / 255;
		}
	},
	TINT {
		@Override
		protected int blendOpaqueChannel(int source, int destination) {
			return source * destination / 255;
		}

		@Override
		public int blend(int sourceArgb, int destinationArgb, float opacity) {
			float amount = clamp01(opacity) * SkinColor.alpha(sourceArgb) / 255.0F;
			int alpha = SkinColor.alpha(destinationArgb);
			int red = lerp((destinationArgb >>> 16) & 0xFF, blendOpaqueChannel((sourceArgb >>> 16) & 0xFF, (destinationArgb >>> 16) & 0xFF), amount);
			int green = lerp((destinationArgb >>> 8) & 0xFF, blendOpaqueChannel((sourceArgb >>> 8) & 0xFF, (destinationArgb >>> 8) & 0xFF), amount);
			int blue = lerp(destinationArgb & 0xFF, blendOpaqueChannel(sourceArgb & 0xFF, destinationArgb & 0xFF), amount);
			return (alpha << 24) | (red << 16) | (green << 8) | blue;
		}
	};

	public int blend(int sourceArgb, int destinationArgb) {
		return blend(sourceArgb, destinationArgb, 1.0F);
	}

	public int blend(int sourceArgb, int destinationArgb, float opacity) {
		int sourceAlpha = Math.round(SkinColor.alpha(sourceArgb) * clamp01(opacity));
		if (sourceAlpha <= 0) {
			return destinationArgb;
		}

		int blended = (sourceAlpha << 24)
			| (blendOpaqueChannel((sourceArgb >>> 16) & 0xFF, (destinationArgb >>> 16) & 0xFF) << 16)
			| (blendOpaqueChannel((sourceArgb >>> 8) & 0xFF, (destinationArgb >>> 8) & 0xFF) << 8)
			| blendOpaqueChannel(sourceArgb & 0xFF, destinationArgb & 0xFF);
		return SkinColor.blendArgb(blended, destinationArgb);
	}

	protected abstract int blendOpaqueChannel(int source, int destination);

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	private static int lerp(int from, int to, float amount) {
		return Math.round(from + (to - from) * amount);
	}
}
