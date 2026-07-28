package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class GlibUi {
	public static final int DEFAULT_LINE = 0x80394655;

	private GlibUi() {
	}

	public static void drawPanel(DrawContext context, int x, int y, int width, int height, int color) {
		drawPanel(context, x, y, width, height, color, DEFAULT_LINE);
	}

	public static void drawPanel(DrawContext context, int x, int y, int width, int height, int color, int lineColor) {
		float alpha = ((color >>> 24) & 0xFF) / 255.0F;
		int line = withAlpha(lineColor, alpha);
		context.fill(x, y, x + width, y + height, color);
		context.fill(x, y, x + width, y + 1, line);
		context.fill(x, y + height - 1, x + width, y + height, withAlpha(0x7010161F, alpha));
		context.fill(x, y, x + 1, y + height, line);
		context.fill(x + width - 1, y, x + width, y + height, line);
	}

	public static void drawSectionTitle(DrawContext context, TextRenderer textRenderer, int x, int y, String label, int accent, int lineColor) {
		context.drawTextWithShadow(textRenderer, Text.literal(label), x, y, 0xFFFFFFFF);
		context.fill(x, y + 13, x + 42, y + 14, accent);
		context.fill(x + 44, y + 13, x + 180, y + 14, withAlpha(lineColor, 0x50));
	}

	public static void drawBadge(DrawContext context, TextRenderer textRenderer, int x, int y, String label, int accent) {
		int badgeWidth = Math.max(84, textRenderer.getWidth(label) + 14);
		context.fill(x, y, x + badgeWidth, y + 18, 0xD0181F2A);
		context.fill(x, y, x + 2, y + 18, accent);
		context.drawTextWithShadow(textRenderer, Text.literal(label), x + 8, y + 5, 0xFFFFFFFF);
	}

	/**
	 * Normalizes text for controls and compact labels. Minecraft's font renderer exposes newline and
	 * other control characters as missing-glyph boxes (often labelled LF), so UI that is deliberately
	 * one line must remove them before measuring or drawing.
	 */
	public static String singleLine(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	public static Text singleLine(Text value) {
		return Text.literal(singleLine(value == null ? "" : value.getString()));
	}

	public static int withAlpha(int color, float alphaMultiplier) {
		int alpha = (color >>> 24) & 0xFF;
		return withAlpha(color, Math.round(alpha * clamp01(alphaMultiplier)));
	}

	public static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}

	public static int mixColor(int from, int to, float amount) {
		float clamped = clamp01(amount);
		int a = mix((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, clamped);
		int r = mix((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, clamped);
		int g = mix((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, clamped);
		int b = mix(from & 0xFF, to & 0xFF, clamped);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public static int lerpInt(int from, int to, float progress) {
		return Math.round(from + (to - from) * clamp01(progress));
	}

	public static float lerp(float from, float to, float progress) {
		return from + (to - from) * clamp01(progress);
	}

	public static double lerp(double from, double to, float progress) {
		return from + (to - from) * clamp01(progress);
	}

	public static float easeOutCubic(float value) {
		float clamped = clamp01(value);
		return 1.0F - (float) Math.pow(1.0F - clamped, 3.0D);
	}

	public static float easeInCubic(float value) {
		float clamped = clamp01(value);
		return clamped * clamped * clamped;
	}

	public static float easeInOutCubic(float value) {
		float clamped = clamp01(value);
		return clamped < 0.5F ? 4.0F * clamped * clamped * clamped : 1.0F - (float) Math.pow(-2.0F * clamped + 2.0F, 3.0D) / 2.0F;
	}

	public static float smoothHover(float current, boolean hovered) {
		return smoothHover(current, hovered, 0.14F);
	}

	public static float smoothHover(float current, boolean hovered, float amount) {
		float target = hovered ? 1.0F : 0.0F;
		return current + (target - current) * clamp01(amount);
	}

	private static int mix(int from, int to, float amount) {
		return Math.round(from + (to - from) * amount);
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
