package io.github.caspe.glib.api.skin;

import java.util.Objects;

public record SkinBrush(int radius, float opacity, SkinBlendMode blendMode, float roughness, float metallic) {
	public static final int MIN_RADIUS = 0;
	public static final int MAX_RADIUS = 24;
	public static final SkinBrush DEFAULT = new SkinBrush(4, 1.0F, SkinBlendMode.NORMAL, 0.0F, 0.0F);

	public SkinBrush(int radius, float opacity, SkinBlendMode blendMode) {
		this(radius, opacity, blendMode, 0.55F, 0.0F);
	}

	public SkinBrush {
		if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
			throw new IllegalArgumentException("Brush radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS);
		}
		if (opacity < 0.0F || opacity > 1.0F) {
			throw new IllegalArgumentException("Brush opacity must be between 0 and 1");
		}
		if (roughness < 0.0F || roughness > 1.0F) {
			throw new IllegalArgumentException("Brush roughness must be between 0 and 1");
		}
		if (metallic < 0.0F || metallic > 1.0F) {
			throw new IllegalArgumentException("Brush metallic must be between 0 and 1");
		}
		blendMode = Objects.requireNonNullElse(blendMode, SkinBlendMode.NORMAL);
	}

	public SkinBrush withRadius(int radius) {
		return new SkinBrush(clampRadius(radius), opacity, blendMode, roughness, metallic);
	}

	public SkinBrush resized(int amount) {
		return withRadius(radius + amount);
	}

	public SkinBrush withOpacity(float opacity) {
		return new SkinBrush(radius, clamp01(opacity), blendMode, roughness, metallic);
	}

	public SkinBrush withBlendMode(SkinBlendMode blendMode) {
		return new SkinBrush(radius, opacity, blendMode, roughness, metallic);
	}

	public SkinBrush withRoughness(float roughness) {
		return new SkinBrush(radius, opacity, blendMode, clamp01(roughness), metallic);
	}

	public SkinBrush withMetallic(float metallic) {
		return new SkinBrush(radius, opacity, blendMode, roughness, clamp01(metallic));
	}

	public static int clampRadius(int radius) {
		return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
