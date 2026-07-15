package io.github.caspe.glib.api.skin;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record SkinSyncSnapshot(UUID uuid, int width, int height, int[] nativePixels, SkinModel model) {
	public SkinSyncSnapshot {
		Objects.requireNonNull(uuid, "uuid");
		Objects.requireNonNull(nativePixels, "nativePixels");
		model = model == null ? SkinModel.WIDE : model;
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Skin dimensions must be positive");
		}
		if (nativePixels.length != width * height) {
			throw new IllegalArgumentException("Expected " + (width * height) + " pixels, got " + nativePixels.length);
		}
		nativePixels = Arrays.copyOf(nativePixels, nativePixels.length);
	}

	@Override
	public int[] nativePixels() {
		return Arrays.copyOf(nativePixels, nativePixels.length);
	}
}
