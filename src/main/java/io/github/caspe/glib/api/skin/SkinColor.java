package io.github.caspe.glib.api.skin;

public final class SkinColor {
	private SkinColor() {
	}

	public static int argbToNative(int argb) {
		int alpha = argb & 0xFF000000;
		int red = (argb >> 16) & 0xFF;
		int green = argb & 0x0000FF00;
		int blue = (argb & 0xFF) << 16;
		return alpha | blue | green | red;
	}

	public static int nativeToArgb(int nativeColor) {
		int alpha = nativeColor & 0xFF000000;
		int red = (nativeColor & 0xFF) << 16;
		int green = nativeColor & 0x0000FF00;
		int blue = (nativeColor >> 16) & 0xFF;
		return alpha | red | green | blue;
	}

	public static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	public static int blendArgb(int top, int bottom) {
		int topAlpha = alpha(top);
		if (topAlpha == 0) {
			return bottom;
		}
		if (topAlpha == 255) {
			return top;
		}

		int bottomAlpha = alpha(bottom);
		int inverseTopAlpha = 255 - topAlpha;
		int outAlpha = topAlpha + bottomAlpha * inverseTopAlpha / 255;
		if (outAlpha == 0) {
			return 0;
		}

		int red = blendChannel((top >>> 16) & 0xFF, topAlpha, (bottom >>> 16) & 0xFF, bottomAlpha, outAlpha);
		int green = blendChannel((top >>> 8) & 0xFF, topAlpha, (bottom >>> 8) & 0xFF, bottomAlpha, outAlpha);
		int blue = blendChannel(top & 0xFF, topAlpha, bottom & 0xFF, bottomAlpha, outAlpha);
		return (outAlpha << 24) | (red << 16) | (green << 8) | blue;
	}

	public static int blendNative(int topNative, int bottomNative) {
		return argbToNative(blendArgb(nativeToArgb(topNative), nativeToArgb(bottomNative)));
	}

	public static int hueShiftArgb(int argb, float degrees) {
		int alpha = argb & 0xFF000000;
		float[] hsb = java.awt.Color.RGBtoHSB((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, null);
		float hue = (hsb[0] + degrees / 360.0F) % 1.0F;
		if (hue < 0.0F) {
			hue += 1.0F;
		}
		return alpha | (java.awt.Color.HSBtoRGB(hue, hsb[1], hsb[2]) & 0x00FFFFFF);
	}

	private static int blendChannel(int top, int topAlpha, int bottom, int bottomAlpha, int outAlpha) {
		return (top * topAlpha + bottom * bottomAlpha * (255 - topAlpha) / 255) / outAlpha;
	}
}
