package io.github.caspe.glib.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.github.caspe.glib.api.hud.GlibHudLists;
import io.github.caspe.glib.api.hud.GlibHudLists.Entry;
import io.github.caspe.glib.api.hud.GlibHudLists.HudList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;

final class GlibHudListRenderer {
	private static final int MARGIN = 8;
	private static final int MIN_WIDTH = 116;
	private static final int ROW_HEIGHT = 11;
	private static final float FADE_SPEED = 0.18F;
	private static final Map<String, Float> ALPHAS = new HashMap<>();

	private GlibHudListRenderer() {
	}

	static void render(DrawContext context, RenderTickCounter tickCounter) {
		Map<String, HudList> lists = GlibHudLists.snapshot();
		for (String id : lists.keySet()) {
			ALPHAS.put(id, approach(ALPHAS.getOrDefault(id, 0.0F), 1.0F));
		}
		Iterator<Map.Entry<String, Float>> iterator = ALPHAS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, Float> entry = iterator.next();
			if (!lists.containsKey(entry.getKey())) {
				float alpha = approach(entry.getValue(), 0.0F);
				if (alpha <= 0.01F) {
					iterator.remove();
				} else {
					entry.setValue(alpha);
				}
			}
		}

		int slot = 0;
		for (HudList list : lists.values()) {
			renderList(context, list, ALPHAS.getOrDefault(list.id(), 1.0F), slot++);
		}
	}

	private static void renderList(DrawContext context, HudList list, float alpha, int slot) {
		if (alpha <= 0.01F || list.entries().isEmpty()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer textRenderer = client.textRenderer;
		int width = Math.max(MIN_WIDTH, textRenderer.getWidth(list.title()) + 24);
		for (Entry entry : list.entries()) {
			width = Math.max(width, textRenderer.getWidth(entry.label()) + textRenderer.getWidth(entry.value()) + 30);
		}
		int height = 20 + list.entries().size() * ROW_HEIGHT;
		int x = x(context, list, width);
		int y = y(context, list, height) + slot * (height + 6);
		int packedAlpha = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);

		context.fill(x, y, x + width, y + height, (packedAlpha << 24) | 0x0A0D12);
		context.drawBorder(x, y, width, height, (Math.round(alpha * 136.0F) << 24) | 0xFFFFFF);
		context.drawTextWithShadow(textRenderer, list.title(), x + 8, y + 7, withAlpha(0xFFE8F0FF, alpha));

		int rowY = y + 19;
		for (Entry entry : list.entries()) {
			context.drawTextWithShadow(textRenderer, entry.label(), x + 8, rowY, withAlpha(entry.labelColor(), alpha));
			context.drawTextWithShadow(textRenderer, entry.value(), x + width - 8 - textRenderer.getWidth(entry.value()), rowY, withAlpha(entry.valueColor(), alpha));
			rowY += ROW_HEIGHT;
		}
	}

	private static int x(DrawContext context, HudList list, int width) {
		return switch (list.anchor()) {
			case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> context.getScaledWindowWidth() - width - MARGIN;
			default -> MARGIN;
		};
	}

	private static int y(DrawContext context, HudList list, int height) {
		return switch (list.anchor()) {
			case TOP_LEFT, TOP_RIGHT -> MARGIN;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> context.getScaledWindowHeight() - height - MARGIN;
			case MIDDLE_LEFT, MIDDLE_RIGHT -> (context.getScaledWindowHeight() - height) / 2;
		};
	}

	private static int withAlpha(int color, float alpha) {
		int packedAlpha = MathHelper.clamp(Math.round(((color >>> 24) & 0xFF) * alpha), 0, 255);
		return (color & 0x00FFFFFF) | (packedAlpha << 24);
	}

	private static float approach(float value, float target) {
		return value + (target - value) * FADE_SPEED;
	}
}
