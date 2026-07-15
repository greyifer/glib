package io.github.caspe.glib.client;

import io.github.caspe.glib.api.timer.GlibTimers;
import io.github.caspe.glib.api.timer.GlibTimers.TimerSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;

final class GlibTimerHud {
	private static final int TOP_MARGIN = 18;
	private static final float APPEAR_SPEED = 0.18F;
	private static final float NUMBER_SPEED = 0.28F;

	private static float alpha;
	private static float scalePulse;
	private static int previousSecond;
	private static int currentSecond = -1;
	private static long lastRevision = -1L;

	private GlibTimerHud() {
	}

	static void render(DrawContext context, RenderTickCounter tickCounter) {
		TimerSnapshot snapshot = GlibTimers.snapshot();
		float targetAlpha = snapshot.visible() ? 1.0F : 0.0F;
		alpha = approach(alpha, targetAlpha, APPEAR_SPEED);
		if (alpha <= 0.01F && !snapshot.visible()) {
			alpha = 0.0F;
			return;
		}

		int seconds = snapshot.wholeSeconds();
		if (snapshot.revision() != lastRevision) {
			previousSecond = seconds;
			currentSecond = seconds;
			scalePulse = 1.0F;
			lastRevision = snapshot.revision();
		} else if (seconds != currentSecond) {
			previousSecond = currentSecond;
			currentSecond = seconds;
			scalePulse = 1.0F;
		}
		scalePulse = approach(scalePulse, 0.0F, NUMBER_SPEED);

		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer textRenderer = client.textRenderer;
		String label = snapshot.label().isBlank() ? "Timer" : snapshot.label();
		String currentText = formatSeconds(currentSecond);
		String previousText = formatSeconds(previousSecond);
		int labelWidth = textRenderer.getWidth(label);
		int timeWidth = Math.max(textRenderer.getWidth(currentText), textRenderer.getWidth(previousText));
		int width = Math.max(labelWidth, Math.round(timeWidth * 1.45F)) + 24;
		int height = 34;
		int x = (context.getScaledWindowWidth() - width) / 2;
		int y = TOP_MARGIN - Math.round((1.0F - alpha) * 8.0F);
		int packedAlpha = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);

		context.fill(x, y, x + width, y + height, (packedAlpha << 24) | 0x10161F);
		context.drawBorder(x, y, width, height, (Math.round(alpha * 170.0F) << 24) | 0xFFFFFF);
		context.drawTextWithShadow(textRenderer, label, x + (width - labelWidth) / 2, y + 5, (packedAlpha << 24) | 0xE8F0FF);

		int centerX = x + width / 2;
		int timeY = y + 18;
		drawSeconds(context, textRenderer, previousText, centerX, timeY - Math.round(scalePulse * 8.0F), alpha * scalePulse);
		drawSeconds(context, textRenderer, currentText, centerX, timeY + Math.round((1.0F - scalePulse) * 2.0F), alpha * (1.0F - scalePulse * 0.25F));
	}

	private static void drawSeconds(DrawContext context, TextRenderer textRenderer, String text, int centerX, int y, float alpha) {
		if (alpha <= 0.01F) {
			return;
		}
		int packedAlpha = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);
		context.drawTextWithShadow(textRenderer, text, centerX - textRenderer.getWidth(text) / 2, y, (packedAlpha << 24) | 0xFFFFFF);
	}

	private static float approach(float value, float target, float amount) {
		return value + (target - value) * amount;
	}

	private static String formatSeconds(int totalSeconds) {
		int seconds = Math.max(0, totalSeconds);
		int minutes = seconds / 60;
		int remainder = seconds % 60;
		return String.format("%d:%02d", minutes, remainder);
	}
}
