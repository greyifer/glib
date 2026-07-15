package io.github.caspe.glib.api.skin;

import java.util.Objects;

public record SkinRegion(String name, int x, int y, int width, int height) {
	public SkinRegion {
		Objects.requireNonNull(name, "name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("Region name cannot be blank");
		}
		if (x < 0 || y < 0 || width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Invalid skin region: " + name);
		}
	}

	public boolean contains(int localX, int localY) {
		return localX >= 0 && localY >= 0 && localX < width && localY < height;
	}
}
