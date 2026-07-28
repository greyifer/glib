package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** A search input styled for G'Lib panels instead of Minecraft's vanilla text-box chrome. */
@Environment(EnvType.CLIENT)
public final class GlibSearchField extends TextFieldWidget {
	private static final int LEFT_INSET = 32;
	private static final int CLEAR_WIDTH = 26;
	private static final int FILL = 0xE8171E28;
	private static final int FILL_FOCUSED = 0xF01B242F;
	private static final int LINE = 0x80505B6A;
	private static final int ACCENT = 0xFF4CDB8A;
	private static final int MUTED = 0xFF8F9AA8;
	private final TextRenderer textRenderer;
	private Text placeholder = Text.literal("Search...");

	public GlibSearchField(TextRenderer textRenderer, int x, int y, int width, int height, Text narration) {
		super(textRenderer, x, y, width, height, narration);
		this.textRenderer = textRenderer;
		setDrawsBackground(false);
		setEditableColor(0xFFFFFFFF);
		setUneditableColor(MUTED);
	}

	/** Sets the hint rendered while this reusable search field is empty. */
	public GlibSearchField placeholder(Text placeholder) {
		this.placeholder = placeholder == null ? Text.empty() : placeholder;
		return this;
	}

	/** Returns a normalized query suitable for menu filtering. */
	public String query() {
		return GlibUi.singleLine(getText()).strip().toLowerCase(java.util.Locale.ROOT);
	}

	/** True when every word in the current query occurs in the supplied searchable text. */
	public boolean matches(String searchableText) {
		String query = query();
		if (query.isBlank()) return true;
		String haystack = GlibUi.singleLine(searchableText == null ? "" : searchableText)
			.toLowerCase(java.util.Locale.ROOT);
		for (String word : query.split("\\s+")) {
			if (!word.isBlank() && !haystack.contains(word)) return false;
		}
		return true;
	}

	@Override
	public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		int outerX = getX();
		int outerY = getY();
		int outerWidth = getWidth();
		int outerHeight = getHeight();
		boolean clearVisible = !getText().isEmpty();
		boolean clearHovered = clearVisible && clearRectContains(mouseX, mouseY);
		int border = isFocused() ? ACCENT : isHovered() ? 0xA067A8FF : LINE;

		context.fill(outerX, outerY, outerX + outerWidth, outerY + outerHeight, isFocused() ? FILL_FOCUSED : FILL);
		context.fill(outerX, outerY, outerX + outerWidth, outerY + 1, border);
		context.fill(outerX, outerY + outerHeight - 1, outerX + outerWidth, outerY + outerHeight, border);
		context.fill(outerX, outerY, outerX + 1, outerY + outerHeight, border);
		context.fill(outerX + outerWidth - 1, outerY, outerX + outerWidth, outerY + outerHeight, border);
		if (isFocused()) context.fill(outerX + 1, outerY + 1, outerX + 3, outerY + outerHeight - 1, ACCENT);

		drawSearchIcon(context, outerX + 10, outerY + (outerHeight - 10) / 2, isFocused() ? ACCENT : MUTED);
		context.fill(outerX + LEFT_INSET - 6, outerY + 6, outerX + LEFT_INSET - 5, outerY + outerHeight - 6, 0x504C5866);
		if (getText().isEmpty() && !isFocused() && !placeholder.getString().isBlank()) {
			String hint = textRenderer.trimToWidth(GlibUi.singleLine(placeholder.getString()),
				Math.max(8, outerWidth - LEFT_INSET - 8));
			context.drawTextWithShadow(textRenderer, Text.literal(hint), outerX + LEFT_INSET,
				outerY + (outerHeight - 8) / 2, MUTED);
		}

		if (clearVisible) {
			int clearX = outerX + outerWidth - CLEAR_WIDTH;
			if (clearHovered) context.fill(clearX + 3, outerY + 3, outerX + outerWidth - 3, outerY + outerHeight - 3, 0xB02A3441);
			drawCloseIcon(context, clearX + 9, outerY + (outerHeight - 8) / 2, clearHovered ? 0xFFFFFFFF : MUTED);
		}

		int textWidth = Math.max(8, outerWidth - LEFT_INSET - (clearVisible ? CLEAR_WIDTH : 8));
		setX(outerX + LEFT_INSET);
		setY(outerY + (outerHeight - 8) / 2);
		setWidth(textWidth);
		super.renderWidget(context, mouseX, mouseY, delta);
		setX(outerX);
		setY(outerY);
		setWidth(outerWidth);
		setHeight(outerHeight);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && visible && active && !getText().isEmpty() && clearRectContains(mouseX, mouseY)) {
			setText("");
			setFocused(true);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		super.onClick(Math.max(getX(), mouseX - LEFT_INSET), mouseY);
	}

	private boolean clearRectContains(double mouseX, double mouseY) {
		return mouseX >= getX() + getWidth() - CLEAR_WIDTH && mouseX < getX() + getWidth()
			&& mouseY >= getY() && mouseY < getY() + getHeight();
	}

	private static void drawSearchIcon(DrawContext context, int x, int y, int color) {
		context.fill(x + 2, y, x + 7, y + 1, color);
		context.fill(x, y + 2, x + 1, y + 7, color);
		context.fill(x + 8, y + 2, x + 9, y + 7, color);
		context.fill(x + 2, y + 8, x + 7, y + 9, color);
		context.fill(x + 7, y + 7, x + 9, y + 9, color);
		context.fill(x + 9, y + 9, x + 12, y + 11, color);
	}

	private static void drawCloseIcon(DrawContext context, int x, int y, int color) {
		for (int offset = 0; offset < 7; offset++) {
			context.fill(x + offset, y + offset, x + offset + 1, y + offset + 1, color);
			context.fill(x + 6 - offset, y + offset, x + 7 - offset, y + offset + 1, color);
		}
	}
}
