package io.github.caspe.glib.api.client.editor;

import io.github.caspe.glib.api.client.ui.GlibRect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Shared rendering and hit geometry for an expandable boolean editor card. */
@Environment(EnvType.CLIENT)
public final class GlibBooleanStateCard {
	private GlibBooleanStateCard() {
	}

	public static GlibRect controlRect(GlibRect cardWorldRect) {
		return new GlibRect(cardWorldRect.x() + 12, cardWorldRect.y() + 70, cardWorldRect.width() - 24, 24);
	}

	public static void render(DrawContext context, TextRenderer textRenderer, GlibRect cardWorldRect, GlibRect screenClip, Content content, boolean enabled, double mouseWorldX, double mouseWorldY, int accentColor, int lineColor) {
		int clipTop = Math.min(screenClip.y() + screenClip.height(), screenClip.y() + Math.max(1, Math.round(screenClip.width() * 38.0F / Math.max(1, cardWorldRect.width()))));
		context.enableScissor(screenClip.x(), clipTop, screenClip.x() + screenClip.width(), screenClip.y() + screenClip.height());
		GlibRect control = controlRect(cardWorldRect);
		boolean hovered = control.contains(mouseWorldX, mouseWorldY);
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(content.description(), cardWorldRect.width() - 24)), cardWorldRect.x() + 12, cardWorldRect.y() + 48, 0xFF9EA7B3);
		context.fill(control.x(), control.y(), control.x() + control.width(), control.y() + control.height(), enabled ? 0xC0234B3D : 0xC02A303B);
		context.drawBorder(control.x(), control.y(), control.width(), control.height(), hovered ? 0xFFFFFFFF : enabled ? accentColor : lineColor);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(enabled ? content.enabledLabel() : content.disabledLabel()), control.x() + control.width() / 2, control.y() + 7, enabled ? accentColor : 0xFFB9C3D1);
		context.disableScissor();
	}

	public record Content(String description, String enabledLabel, String disabledLabel) {
	}
}
