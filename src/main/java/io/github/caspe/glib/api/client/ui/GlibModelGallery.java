package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A reusable, searchable model gallery canvas used by editor screens.
 *
 * <p>The host owns the search widget and the model preview objects. This class owns the
 * gallery layout, clipping, scrolling, hit testing, hover treatment, and tooltips so every
 * editor presents model libraries with the same interaction language.</p>
 */
@Environment(EnvType.CLIENT)
public final class GlibModelGallery<T> {
	public static final int CARD_SIZE = 104;
	public static final int CARD_GAP = 8;
	public static final int PANEL_PADDING = 18;
	public static final int SEARCH_TOP = 51;
	public static final int SEARCH_HEIGHT = 22;
	public static final int GRID_TOP = 91;
	private static final int BOTTOM_PADDING = 12;
	private static final int MUTED = 0xFF9EA7B3;
	private static final int SEARCH_BACKGROUND = 0xF0080B10;
	private static final int CARD_BACKGROUND = 0xD019202B;
	private static final int CARD_HOVER = 0xE0202B38;
	private static final int CARD_SELECTED = 0xF022303D;

	private int scroll;

	@FunctionalInterface
	public interface EntryRenderer<T> {
		void render(DrawContext context, T entry, GlibRect card, boolean hovered, boolean selected);
	}

	public int scroll() {
		return scroll;
	}

	public void resetScroll() {
		scroll = 0;
	}

	public GlibRect searchBox(GlibRect panel) {
		return new GlibRect(panel.x() + PANEL_PADDING, panel.y() + SEARCH_TOP,
			Math.max(1, panel.width() - PANEL_PADDING * 2), SEARCH_HEIGHT);
	}

	public GlibRect viewport(GlibRect panel) {
		return new GlibRect(panel.x() + 12, panel.y() + GRID_TOP,
			Math.max(1, panel.width() - 24), Math.max(1, panel.height() - GRID_TOP - BOTTOM_PADDING));
	}

	public void layoutSearchField(TextFieldWidget field, GlibRect panel) {
		if (field == null) return;
		GlibRect box = searchBox(panel);
		field.setPosition(box.x() + 6, box.y() + 7);
		field.setDimensions(Math.max(1, box.width() - 12), 10);
	}

	public T entryAt(GlibRect panel, double mouseX, double mouseY, List<T> entries) {
		GlibRect viewport = viewport(panel);
		if (!viewport.contains(mouseX, mouseY)) return null;
		int columns = columns(panel);
		int column = (int) ((mouseX - panel.x() - PANEL_PADDING) / (CARD_SIZE + CARD_GAP));
		int row = (int) ((mouseY - panel.y() - GRID_TOP + scroll) / (CARD_SIZE + CARD_GAP));
		if (column < 0 || column >= columns || row < 0) return null;
		int localX = (int) (mouseX - panel.x() - PANEL_PADDING - column * (CARD_SIZE + CARD_GAP));
		int localY = (int) (mouseY - panel.y() - GRID_TOP + scroll - row * (CARD_SIZE + CARD_GAP));
		if (localX < 0 || localY < 0 || localX >= CARD_SIZE || localY >= CARD_SIZE) return null;
		int index = row * columns + column;
		return index >= 0 && index < entries.size() ? entries.get(index) : null;
	}

	public boolean mouseScrolled(GlibRect panel, List<T> entries, double mouseX, double mouseY, double vertical) {
		if (!viewport(panel).contains(mouseX, mouseY)) return false;
		int max = maxScroll(panel, entries.size());
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(vertical * 52.0D)));
		return true;
	}

	public void render(
		DrawContext context,
		TextRenderer textRenderer,
		GlibRect panel,
		String title,
		String searchLabel,
		String emptyLabel,
		List<T> entries,
		T selected,
		TextFieldWidget searchField,
		int mouseX,
		int mouseY,
		int accent,
		int line,
		int reservedTopRight,
		int scissorYOffset,
		EntryRenderer<T> entryRenderer,
		Function<T, String> tooltipLabel
	) {
		clampScroll(panel, entries.size());
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), 0xFA121720, line);
		context.drawTextWithShadow(textRenderer, Text.literal(title), panel.x() + PANEL_PADDING, panel.y() + 15, 0xFFFFFFFF);
		String count = entries.size() + (entries.size() == 1 ? " model" : " models");
		int countX = panel.x() + panel.width() - PANEL_PADDING - Math.max(0, reservedTopRight) - textRenderer.getWidth(count);
		if (countX > panel.x() + PANEL_PADDING + textRenderer.getWidth(title) + 12) {
			context.drawTextWithShadow(textRenderer, Text.literal(count), countX, panel.y() + 15, MUTED);
		}

		GlibRect searchBox = searchBox(panel);
		context.drawTextWithShadow(textRenderer, Text.literal(searchLabel), searchBox.x(), searchBox.y() - 13, 0xFF8E98A6);
		int searchBorder = searchField != null && searchField.isFocused() ? accent : line;
		context.fill(searchBox.x() - 1, searchBox.y() - 1, searchBox.x() + searchBox.width() + 1, searchBox.y() + searchBox.height() + 1, searchBorder);
		context.fill(searchBox.x(), searchBox.y(), searchBox.x() + searchBox.width(), searchBox.y() + searchBox.height(), SEARCH_BACKGROUND);

		GlibRect viewport = viewport(panel);
		context.enableScissor(viewport.x(), viewport.y() + scissorYOffset,
			viewport.x() + viewport.width(), viewport.y() + viewport.height() + scissorYOffset);
		if (entries.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(emptyLabel), panel.x() + panel.width() / 2,
				viewport.y() + Math.max(12, viewport.height() / 2 - 4), MUTED);
		} else {
			int columns = columns(panel);
			for (int index = 0; index < entries.size(); index++) {
				GlibRect card = cardRect(panel, index, columns);
				if (card.y() + card.height() <= viewport.y() || card.y() >= viewport.y() + viewport.height()) continue;
				T entry = entries.get(index);
				boolean hovered = card.contains(mouseX, mouseY) && viewport.contains(mouseX, mouseY);
				boolean isSelected = Objects.equals(entry, selected);
				GlibUi.drawPanel(context, card.x(), card.y(), card.width(), card.height(),
					isSelected ? CARD_SELECTED : hovered ? CARD_HOVER : CARD_BACKGROUND,
					isSelected || hovered ? accent : line);
				entryRenderer.render(context, entry, card, hovered, isSelected);
			}
		}
		context.disableScissor();

		T hovered = entryAt(panel, mouseX, mouseY, entries);
		if (hovered != null) {
			String tooltip = tooltipLabel.apply(hovered);
			if (tooltip != null && !tooltip.isBlank()) context.drawTooltip(textRenderer, Text.literal(tooltip), mouseX, mouseY);
		}
	}

	private GlibRect cardRect(GlibRect panel, int index, int columns) {
		int row = index / columns;
		int column = index % columns;
		return new GlibRect(panel.x() + PANEL_PADDING + column * (CARD_SIZE + CARD_GAP),
			panel.y() + GRID_TOP + row * (CARD_SIZE + CARD_GAP) - scroll, CARD_SIZE, CARD_SIZE);
	}

	private int columns(GlibRect panel) {
		return Math.max(1, (panel.width() - PANEL_PADDING * 2 + CARD_GAP) / (CARD_SIZE + CARD_GAP));
	}

	private int maxScroll(GlibRect panel, int entryCount) {
		int rows = (entryCount + columns(panel) - 1) / columns(panel);
		int contentHeight = rows == 0 ? 0 : rows * (CARD_SIZE + CARD_GAP) - CARD_GAP;
		return Math.max(0, contentHeight - viewport(panel).height());
	}

	private void clampScroll(GlibRect panel, int entryCount) {
		scroll = Math.max(0, Math.min(maxScroll(panel, entryCount), scroll));
	}
}
