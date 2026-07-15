package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public record GlibRect(int x, int y, int width, int height) {
	public static GlibRect empty() {
		return new GlibRect(0, 0, 0, 0);
	}

	public boolean contains(double pointX, double pointY) {
		return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
	}

	public GlibRect offset(int xOffset, int yOffset) {
		return new GlibRect(x + xOffset, y + yOffset, width, height);
	}

	public boolean isEmpty() {
		return width <= 0 || height <= 0;
	}

	public GlibRect interpolate(GlibRect to, float progress) {
		if (isEmpty() || progress >= 1.0F) {
			return to;
		}
		return new GlibRect(
			GlibUi.lerpInt(x, to.x(), progress),
			GlibUi.lerpInt(y, to.y(), progress),
			GlibUi.lerpInt(width, to.width(), progress),
			GlibUi.lerpInt(height, to.height(), progress)
		);
	}
}
