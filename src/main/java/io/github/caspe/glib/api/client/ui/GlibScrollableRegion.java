package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;

@Environment(EnvType.CLIENT)
public final class GlibScrollableRegion {
	private static final int DEFAULT_SCROLL_STEP = 28;
	private static final int DEFAULT_THUMB_MIN_HEIGHT = 22;

	private int scrollOffset;
	private int scrollStep = DEFAULT_SCROLL_STEP;
	private int thumbMinHeight = DEFAULT_THUMB_MIN_HEIGHT;

	public int scrollOffset() {
		return scrollOffset;
	}

	public void setScrollOffset(int scrollOffset) {
		this.scrollOffset = Math.max(0, scrollOffset);
	}

	public void reset() {
		scrollOffset = 0;
	}

	public GlibScrollableRegion scrollStep(int scrollStep) {
		this.scrollStep = Math.max(1, scrollStep);
		return this;
	}

	public GlibScrollableRegion thumbMinHeight(int thumbMinHeight) {
		this.thumbMinHeight = Math.max(1, thumbMinHeight);
		return this;
	}

	public int contentY(int baseY) {
		return baseY - scrollOffset;
	}

	public int maxScroll(GlibRect viewport, int contentHeight) {
		return Math.max(0, contentHeight - Math.max(0, viewport.height()));
	}

	public void clamp(GlibRect viewport, int contentHeight) {
		scrollOffset = clampOffset(viewport, contentHeight, scrollOffset);
	}

	public int clampOffset(GlibRect viewport, int contentHeight, int value) {
		return Math.max(0, Math.min(maxScroll(viewport, contentHeight), value));
	}

	public boolean handleMouseScrolled(GlibRect viewport, int contentHeight, double mouseX, double mouseY, double verticalAmount) {
		if (!viewport.contains(mouseX, mouseY)) {
			return false;
		}
		int maxScroll = maxScroll(viewport, contentHeight);
		if (maxScroll <= 0) {
			return false;
		}
		scrollOffset = clampOffset(viewport, contentHeight, scrollOffset - (int) Math.round(verticalAmount * scrollStep));
		return true;
	}

	public void enableScissor(DrawContext context, GlibRect viewport, int screenOffsetY) {
		GlibRect shifted = viewport.offset(0, screenOffsetY);
		context.enableScissor(shifted.x(), shifted.y(), shifted.x() + shifted.width(), shifted.y() + shifted.height());
	}

	public void disableScissor(DrawContext context) {
		context.disableScissor();
	}

	public void drawScrollbar(DrawContext context, GlibRect viewport, int contentHeight, int accentColor) {
		int maxScroll = maxScroll(viewport, contentHeight);
		if (maxScroll <= 0) {
			return;
		}
		int trackX = viewport.x() + viewport.width() - 3;
		int trackY = viewport.y();
		int trackHeight = viewport.height();
		context.fill(trackX, trackY, trackX + 2, trackY + trackHeight, 0x40394655);
		int thumbHeight = Math.max(thumbMinHeight, Math.round(trackHeight * (viewport.height() / (float) (viewport.height() + maxScroll))));
		int thumbY = trackY + Math.round((trackHeight - thumbHeight) * (scrollOffset / (float) maxScroll));
		context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, GlibUi.withAlpha(accentColor, 0xA0));
	}

	public static boolean intersects(GlibRect first, GlibRect second) {
		return first.x() < second.x() + second.width()
			&& first.x() + first.width() > second.x()
			&& first.y() < second.y() + second.height()
			&& first.y() + first.height() > second.y();
	}

	public static GlibRect widgetRect(ClickableWidget widget) {
		return new GlibRect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
	}

	public static boolean widgetIntersectsViewport(ClickableWidget widget, GlibRect viewport) {
		return widget != null && intersects(widgetRect(widget), viewport);
	}

	public static void setWidgetVisible(ClickableWidget widget, boolean visible) {
		if (widget == null) {
			return;
		}
		widget.visible = visible;
		widget.active = visible;
	}
}
