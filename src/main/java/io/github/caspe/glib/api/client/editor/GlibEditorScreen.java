package io.github.caspe.glib.api.client.editor;

import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibScrollableRegion;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * G'Lib's full-page editor shell. Mods provide pages, sections, and value bindings while G'Lib owns
 * navigation, scrolling, controls, layout, and rendering.
 */
@Environment(EnvType.CLIENT)
public final class GlibEditorScreen extends Screen {
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int ROW = 0xC019222E;
	private static final int ROW_HOVER = 0xE01D2835;
	private static final int LINE = 0x80394655;
	private static final int MUTED = 0xFF9EA7B3;
	private static final int CONTROL_WIDTH = 244;
	private static final int ROW_HEIGHT = 54;

	private final Screen parent;
	private final Text subtitle;
	private final int accentColor;
	private final List<Page> pages;
	private final Runnable saveAction;
	private final Runnable cancelAction;
	private final GlibScrollableRegion pageScroll = new GlibScrollableRegion().scrollStep(30);
	private final GlibScrollableRegion contentScroll = new GlibScrollableRegion().scrollStep(36);
	private final List<WidgetPlacement> pageWidgets = new ArrayList<>();
	private final List<WidgetPlacement> entryWidgets = new ArrayList<>();
	private int selectedPage;
	private int contentHeight;
	private GlibButton cancelButton;
	private GlibButton saveButton;
	private boolean completed;

	private GlibEditorScreen(Builder builder) {
		super(builder.title);
		this.parent = builder.parent;
		this.subtitle = builder.subtitle;
		this.accentColor = builder.accentColor;
		this.pages = builder.pages.isEmpty()
			? List.of(new Page(Text.literal("Settings"), Text.empty(), List.of()))
			: List.copyOf(builder.pages);
		this.saveAction = builder.saveAction;
		this.cancelAction = builder.cancelAction;
	}

	public static Builder builder(Screen parent, Text title) {
		return new Builder(parent, title);
	}

	@Override
	protected void init() {
		rebuildControls();
	}

	private void rebuildControls() {
		clearChildren();
		pageWidgets.clear();
		entryWidgets.clear();

		GlibRect sidebar = sidebarViewport();
		for (int index = 0; index < pages.size(); index++) {
			int pageIndex = index;
			Page page = pages.get(index);
			GlibButton button = new GlibButton(sidebar.x() + 8, sidebar.y() + index * 32,
				sidebar.width() - 18, 24, page.title(), accentColor, () -> selectPage(pageIndex));
			addDrawableChild(button);
			pageWidgets.add(new WidgetPlacement(button, sidebar.y() + index * 32, null));
		}

		buildEntryWidgets();

		GlibRect panel = panelRect();
		int footerY = panel.y() + panel.height() - 37;
		cancelButton = addDrawableChild(new GlibButton(panel.x() + 18, footerY, 132, 22,
			Text.literal("Back"), 0xFFFFC857, this::cancelAndClose));
		saveButton = addDrawableChild(new GlibButton(panel.x() + panel.width() - 168, footerY, 150, 22,
			Text.literal("Save & Back"), accentColor, this::saveAndClose));

		layoutWidgets();
	}

	private void buildEntryWidgets() {
		Page page = pages.get(selectedPage);
		GlibRect viewport = contentViewport();
		int y = viewport.y() + 42;
		for (Section section : page.sections()) {
			y += 30;
			for (Entry entry : section.entries()) {
				ClickableWidget widget = createWidget(entry, viewport.x() + viewport.width() - CONTROL_WIDTH - 14,
					y + 8, CONTROL_WIDTH, 24);
				if (widget != null) {
					addDrawableChild(widget);
					entryWidgets.add(new WidgetPlacement(widget, y + 8, entry));
				}
				y += entry instanceof LabelEntry ? 38 : ROW_HEIGHT;
			}
			y += 12;
		}
		contentHeight = Math.max(viewport.height(), y - viewport.y() + 8);
		contentScroll.clamp(viewport, contentHeight);
	}

	private ClickableWidget createWidget(Entry entry, int x, int y, int width, int height) {
		if (entry instanceof ToggleEntry toggle) {
			return new GlibButton(x, y, width, height, toggle.formatter().apply(toggle.value().get()), accentColor,
				() -> toggle.setter().accept(!toggle.value().get()));
		}
		if (entry instanceof CycleEntry cycle) {
			return new GlibButton(x, y, width, height, cycle.valueText().get(), accentColor, cycle.cycle());
		}
		if (entry instanceof SliderEntry slider) {
			return new GlibSlider(x, y, width, height, slider.value(), slider.setter(), slider.valueText(), accentColor);
		}
		if (entry instanceof TextEntry text) {
			TextFieldWidget field = new TextFieldWidget(textRenderer, x, y + 2, width, height - 4, text.label());
			field.setMaxLength(text.maxLength());
			field.setText(text.value().get());
			field.setChangedListener(value -> {
				boolean valid = text.validator().test(value);
				field.setEditableColor(valid ? 0xFFFFFFFF : 0xFFFF718D);
				if (valid) text.setter().accept(value);
			});
			return field;
		}
		if (entry instanceof ActionEntry action) {
			return new GlibButton(x, y, width, height, action.buttonText(), accentColor, action.action());
		}
		return null;
	}

	private void selectPage(int index) {
		if (index < 0 || index >= pages.size() || index == selectedPage) return;
		selectedPage = index;
		contentScroll.reset();
		rebuildControls();
	}

	private void layoutWidgets() {
		GlibRect pageViewport = sidebarViewport();
		for (WidgetPlacement placement : pageWidgets) {
			int y = placement.baseY() - pageScroll.scrollOffset();
			placement.widget().setY(y);
			boolean visible = GlibScrollableRegion.intersects(
				new GlibRect(placement.widget().getX(), y, placement.widget().getWidth(), placement.widget().getHeight()),
				pageViewport);
			GlibScrollableRegion.setWidgetVisible(placement.widget(), visible);
		}

		GlibRect viewport = contentViewport();
		for (WidgetPlacement placement : entryWidgets) {
			int y = placement.baseY() - contentScroll.scrollOffset();
			placement.widget().setY(y);
			boolean visible = GlibScrollableRegion.intersects(
				new GlibRect(placement.widget().getX(), y, placement.widget().getWidth(), placement.widget().getHeight()),
				viewport);
			GlibScrollableRegion.setWidgetVisible(placement.widget(), visible);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderShell(context);
		renderPageSelection(context);
		renderContent(context, mouseX, mouseY);
		refreshDynamicWidgets();
		super.render(context, mouseX, mouseY, delta);
		pageScroll.drawScrollbar(context, sidebarViewport(), pages.size() * 32, accentColor);
		contentScroll.drawScrollbar(context, contentViewport(), contentHeight, accentColor);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// G'Lib draws its own opaque/translucent chrome. Minecraft's deferred blur would also
		// blur G'Lib's text and panels while leaving child widgets sharp.
	}

	private void renderShell(DrawContext context) {
		GlibRect panel = panelRect();
		GlibRect sidebar = sidebarRect();
		GlibRect content = contentRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, accentColor);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 22, panel.y() + 20, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, subtitle, panel.x() + 22, panel.y() + 37, MUTED);
		GlibUi.drawPanel(context, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height(), PANEL_SOFT, LINE);
		GlibUi.drawPanel(context, content.x(), content.y(), content.width(), content.height(), PANEL_SOFT, LINE);
	}

	private void renderPageSelection(DrawContext context) {
		if (selectedPage < 0 || selectedPage >= pageWidgets.size()) return;
		WidgetPlacement selected = pageWidgets.get(selectedPage);
		int y = selected.baseY() - pageScroll.scrollOffset();
		GlibRect viewport = sidebarViewport();
		if (y + 24 > viewport.y() && y < viewport.y() + viewport.height()) {
			context.fill(viewport.x() + 4, y - 2, viewport.x() + 7, y + 26, accentColor);
		}
	}

	private void renderContent(DrawContext context, int mouseX, int mouseY) {
		Page page = pages.get(selectedPage);
		GlibRect viewport = contentViewport();
		int scroll = contentScroll.scrollOffset();
		int y = viewport.y() + 14 - scroll;
		context.enableScissor(viewport.x(), viewport.y(), viewport.x() + viewport.width(), viewport.y() + viewport.height());
		context.drawTextWithShadow(textRenderer, page.title(), viewport.x() + 14, y, 0xFFFFFFFF);
		if (!page.subtitle().getString().isBlank()) {
			context.drawTextWithShadow(textRenderer, page.subtitle(), viewport.x() + 14, y + 15, MUTED);
		}
		y += 42;
		for (Section section : page.sections()) {
			GlibUi.drawSectionTitle(context, textRenderer, viewport.x() + 14, y,
				section.title().getString(), accentColor, LINE);
			if (!section.description().getString().isBlank()) {
				String description = textRenderer.trimToWidth(section.description().getString(), viewport.width() - 220);
				context.drawTextWithShadow(textRenderer, Text.literal(description), viewport.x() + 206, y, MUTED);
			}
			y += 30;
			for (Entry entry : section.entries()) {
				int rowHeight = entry instanceof LabelEntry ? 32 : ROW_HEIGHT - 4;
				boolean hovered = mouseX >= viewport.x() + 10 && mouseX < viewport.x() + viewport.width() - 10
					&& mouseY >= y && mouseY < y + rowHeight;
				context.fill(viewport.x() + 10, y, viewport.x() + viewport.width() - 10, y + rowHeight,
					hovered ? ROW_HOVER : ROW);
				context.fill(viewport.x() + 10, y, viewport.x() + 12, y + rowHeight,
					entry.available().getAsBoolean() ? GlibUi.withAlpha(accentColor, 0xB0) : 0xFF647282);
				int labelColor = entry.available().getAsBoolean() ? 0xFFFFFFFF : MUTED;
				context.drawTextWithShadow(textRenderer, entry.label(), viewport.x() + 22, y + 9, labelColor);
				if (!entry.description().getString().isBlank()) {
					String description = textRenderer.trimToWidth(entry.description().getString(),
						Math.max(80, viewport.width() - CONTROL_WIDTH - 70));
					context.drawTextWithShadow(textRenderer, Text.literal(description), viewport.x() + 22, y + 26, MUTED);
				}
				y += entry instanceof LabelEntry ? 38 : ROW_HEIGHT;
			}
			y += 12;
		}
		if (page.sections().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("No settings are available here."),
				viewport.x() + viewport.width() / 2, viewport.y() + viewport.height() / 2, MUTED);
		}
		context.disableScissor();
	}

	private void refreshDynamicWidgets() {
		for (WidgetPlacement placement : entryWidgets) {
			Entry entry = placement.entry();
			if (entry == null) continue;
			placement.widget().active = entry.available().getAsBoolean();
			if (entry instanceof ToggleEntry toggle) {
				placement.widget().setMessage(toggle.formatter().apply(toggle.value().get()));
			} else if (entry instanceof CycleEntry cycle) {
				placement.widget().setMessage(cycle.valueText().get());
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		boolean handled;
		if (sidebarViewport().contains(mouseX, mouseY)) {
			handled = pageScroll.handleMouseScrolled(sidebarViewport(), pages.size() * 32,
				mouseX, mouseY, verticalAmount);
		} else {
			handled = contentScroll.handleMouseScrolled(contentViewport(), contentHeight,
				mouseX, mouseY, verticalAmount);
		}
		if (handled) {
			layoutWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			cancelAndClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		cancelAndClose();
	}

	private void saveAndClose() {
		if (completed) return;
		completed = true;
		saveAction.run();
		if (client != null) client.setScreen(parent);
	}

	private void cancelAndClose() {
		if (completed) return;
		completed = true;
		cancelAction.run();
		if (client != null) client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(1120, width - 24);
		int panelHeight = Math.min(720, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private GlibRect sidebarRect() {
		GlibRect panel = panelRect();
		return new GlibRect(panel.x() + 16, panel.y() + 62, Math.min(206, Math.max(164, panel.width() / 5)), panel.height() - 108);
	}

	private GlibRect sidebarViewport() {
		GlibRect rect = sidebarRect();
		return new GlibRect(rect.x() + 4, rect.y() + 8, rect.width() - 8, rect.height() - 16);
	}

	private GlibRect contentRect() {
		GlibRect panel = panelRect();
		GlibRect sidebar = sidebarRect();
		int x = sidebar.x() + sidebar.width() + 12;
		return new GlibRect(x, sidebar.y(), panel.x() + panel.width() - 16 - x, sidebar.height());
	}

	private GlibRect contentViewport() {
		GlibRect rect = contentRect();
		return new GlibRect(rect.x() + 4, rect.y() + 4, rect.width() - 8, rect.height() - 8);
	}

	private record WidgetPlacement(ClickableWidget widget, int baseY, Entry entry) {
	}

	public record Page(Text title, Text subtitle, List<Section> sections) {
		public Page {
			title = Objects.requireNonNull(title, "title");
			subtitle = subtitle == null ? Text.empty() : subtitle;
			sections = sections == null ? List.of() : List.copyOf(sections);
		}
	}

	public record Section(Text title, Text description, List<Entry> entries) {
		public Section {
			title = Objects.requireNonNull(title, "title");
			description = description == null ? Text.empty() : description;
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	public sealed interface Entry permits ToggleEntry, CycleEntry, SliderEntry, TextEntry, ActionEntry, LabelEntry {
		Text label();
		Text description();
		BooleanSupplier available();
	}

	public record ToggleEntry(Text label, Text description, BooleanSupplier available, Supplier<Boolean> value,
			Consumer<Boolean> setter, Function<Boolean, Text> formatter) implements Entry {
	}

	public record CycleEntry(Text label, Text description, BooleanSupplier available, Supplier<Text> valueText,
			Runnable cycle) implements Entry {
	}

	public record SliderEntry(Text label, Text description, BooleanSupplier available, DoubleSupplier value,
			DoubleConsumer setter, DoubleFunction<Text> valueText) implements Entry {
	}

	public record TextEntry(Text label, Text description, BooleanSupplier available, Supplier<String> value,
			Consumer<String> setter, Predicate<String> validator, int maxLength) implements Entry {
	}

	public record ActionEntry(Text label, Text description, BooleanSupplier available, Text buttonText,
			Runnable action) implements Entry {
	}

	public record LabelEntry(Text label, Text description, BooleanSupplier available) implements Entry {
	}

	public static final class Builder {
		private final Screen parent;
		private final Text title;
		private Text subtitle = Text.empty();
		private int accentColor = 0xFF45D483;
		private final List<Page> pages = new ArrayList<>();
		private Runnable saveAction = () -> {};
		private Runnable cancelAction = () -> {};

		private Builder(Screen parent, Text title) {
			this.parent = parent;
			this.title = Objects.requireNonNull(title, "title");
		}

		public Builder subtitle(Text subtitle) {
			this.subtitle = subtitle == null ? Text.empty() : subtitle;
			return this;
		}

		public Builder accentColor(int accentColor) {
			this.accentColor = accentColor;
			return this;
		}

		public Builder page(Page page) {
			this.pages.add(Objects.requireNonNull(page, "page"));
			return this;
		}

		public Builder pages(List<Page> pages) {
			if (pages != null) this.pages.addAll(pages);
			return this;
		}

		public Builder onSave(Runnable saveAction) {
			this.saveAction = saveAction == null ? () -> {} : saveAction;
			return this;
		}

		public Builder onCancel(Runnable cancelAction) {
			this.cancelAction = cancelAction == null ? () -> {} : cancelAction;
			return this;
		}

		public GlibEditorScreen build() {
			return new GlibEditorScreen(this);
		}
	}

	private static final class GlibSlider extends SliderWidget {
		private final DoubleConsumer setter;
		private final DoubleFunction<Text> valueText;
		private final int accentColor;

		private GlibSlider(int x, int y, int width, int height, DoubleSupplier value,
				DoubleConsumer setter, DoubleFunction<Text> valueText, int accentColor) {
			super(x, y, width, height, valueText.apply(clamp01(value.getAsDouble())), clamp01(value.getAsDouble()));
			this.setter = setter;
			this.valueText = valueText;
			this.accentColor = accentColor;
		}

		@Override
		protected void updateMessage() {
			setMessage(valueText.apply(value));
		}

		@Override
		protected void applyValue() {
			setter.accept(value);
		}

		@Override
		public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			int fill = active ? 0xE01D2430 : 0x8030353D;
			int border = active && isHovered() ? accentColor : 0x804B5666;
			context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
			context.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
			int progress = (int) Math.round((getWidth() - 4) * value);
			context.fill(getX() + 2, getY() + getHeight() - 3, getX() + 2 + progress,
				getY() + getHeight() - 1, accentColor);
			int handleX = getX() + 2 + progress;
			context.fill(handleX - 1, getY() + 3, handleX + 1, getY() + getHeight() - 3, accentColor);
			TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
			String label = renderer.trimToWidth(getMessage().getString(), getWidth() - 14);
			context.drawCenteredTextWithShadow(renderer, Text.literal(label), getX() + getWidth() / 2,
				getY() + (getHeight() - 8) / 2, active ? 0xFFFFFFFF : MUTED);
		}

		private static double clamp01(double value) {
			return Math.max(0.0D, Math.min(1.0D, value));
		}
	}
}
