package io.github.caspe.glib.api.skin;

import java.util.Objects;

public record SkinPaintTarget(SkinRegion region, int localX, int localY) {
	public SkinPaintTarget {
		Objects.requireNonNull(region, "region");
		if (!region.contains(localX, localY)) {
			throw new IndexOutOfBoundsException("Paint target " + localX + "," + localY + " is outside region " + region.name());
		}
	}

	public int textureX() {
		return region.x() + localX;
	}

	public int textureY() {
		return region.y() + localY;
	}
}
