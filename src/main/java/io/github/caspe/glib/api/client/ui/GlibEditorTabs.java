package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Environment(EnvType.CLIENT)
public final class GlibEditorTabs<T> {
	public interface Labelled {
		String label();
	}

	public static final float DEFAULT_FADE_SECONDS = GlibLoadingScreen.DEFAULT_FADE_SECONDS;
	public static final float DEFAULT_LOADING_SECONDS = GlibLoadingScreen.DEFAULT_LOADING_SECONDS;

	private final List<T> tabs;
	private final Function<T, String> labelResolver;
	private final int accentColor;
	private final int lineColor;
	private final float fadeSeconds;
	private final float loadingSeconds;
	private final GlibLoadingScreen loadingScreen;

	private T activeTab;
	private T transitionFromTab;
	private T transitionToTab;
	private boolean transitionActive;
	private boolean transitionCommitted;

	public GlibEditorTabs(List<T> tabs, Function<T, String> labelResolver, T initialTab, int accentColor, int lineColor) {
		this(tabs, labelResolver, initialTab, accentColor, lineColor, DEFAULT_FADE_SECONDS, DEFAULT_LOADING_SECONDS);
	}

	public GlibEditorTabs(List<T> tabs, Function<T, String> labelResolver, T initialTab, int accentColor, int lineColor, float fadeSeconds, float loadingSeconds) {
		if (tabs == null || tabs.isEmpty()) {
			throw new IllegalArgumentException("tabs must not be empty");
		}
		this.tabs = List.copyOf(tabs);
		this.labelResolver = Objects.requireNonNull(labelResolver, "labelResolver");
		this.accentColor = accentColor;
		this.lineColor = lineColor;
		this.fadeSeconds = Math.max(0.0F, fadeSeconds);
		this.loadingSeconds = Math.max(0.0F, loadingSeconds);
		this.loadingScreen = new GlibLoadingScreen(accentColor, 0xFF101722, this.fadeSeconds, this.loadingSeconds);
		this.activeTab = tabs.contains(initialTab) ? initialTab : tabs.getFirst();
		this.transitionFromTab = this.activeTab;
		this.transitionToTab = this.activeTab;
	}

	public static <T extends Labelled> GlibEditorTabs<T> labelled(List<T> tabs, T initialTab, int accentColor, int lineColor) {
		return new GlibEditorTabs<>(tabs, Labelled::label, initialTab, accentColor, lineColor);
	}

	public List<T> tabs() {
		return tabs;
	}

	public T activeTab() {
		return activeTab;
	}

	public T selectedTab() {
		return transitionActive ? transitionToTab : activeTab;
	}

	public boolean transitionActive() {
		return transitionActive;
	}

	public boolean transitionCommitted() {
		return transitionCommitted;
	}

	public T transitionFromTab() {
		return transitionFromTab;
	}

	public T transitionToTab() {
		return transitionToTab;
	}

	public boolean transitionHidesWidgets() {
		return transitionActive && !transitionCommitted && loadingScreen.hidesWidgets();
	}

	public float transitionElapsedSeconds() {
		return loadingScreen.elapsedSeconds();
	}

	public float transitionLoadingSeconds() {
		return loadingScreen.loadingSeconds();
	}

	public float transitionTotalSeconds() {
		return loadingScreen.totalSeconds();
	}

	public void select(T tab) {
		select(tab, loadingSeconds);
	}

	public void select(T tab, float transitionLoadingSeconds) {
		if (tab == null || !tabs.contains(tab)) {
			return;
		}
		if (transitionActive && Objects.equals(transitionToTab, tab)) {
			return;
		}
		if (transitionActive && !transitionCommitted && Objects.equals(activeTab, tab)) {
			transitionActive = false;
			loadingScreen.cancel();
			return;
		}
		if (!transitionActive && Objects.equals(activeTab, tab)) {
			return;
		}
		transitionFromTab = activeTab;
		transitionToTab = tab;
		transitionActive = true;
		transitionCommitted = false;
		loadingScreen.start("Opening " + label(tab) + "...", transitionLoadingSeconds);
	}

	public void switchImmediately(T tab) {
		if (tab == null || !tabs.contains(tab)) {
			return;
		}
		transitionActive = false;
		transitionCommitted = false;
		loadingScreen.cancel();
		activeTab = tab;
		transitionFromTab = tab;
		transitionToTab = tab;
	}

	public void tick() {
		if (!transitionActive) {
			return;
		}
		if (!transitionCommitted && loadingScreen.commitReady()) {
			activeTab = transitionToTab;
			transitionCommitted = true;
		}
		if (loadingScreen.finished()) {
			activeTab = transitionToTab;
			transitionFromTab = activeTab;
			transitionActive = false;
			transitionCommitted = false;
			loadingScreen.cancel();
		}
	}

	public T tabAt(Function<T, GlibRect> rectResolver, double mouseX, double mouseY) {
		for (T tab : tabs) {
			if (rectResolver.apply(tab).contains(mouseX, mouseY)) {
				return tab;
			}
		}
		return null;
	}

	public boolean handleClick(Function<T, GlibRect> rectResolver, double mouseX, double mouseY, int button) {
		if (button != 0) {
			return false;
		}
		T tab = tabAt(rectResolver, mouseX, mouseY);
		if (tab == null) {
			return false;
		}
		select(tab);
		return true;
	}

	public void renderTabs(DrawContext context, TextRenderer textRenderer, Function<T, GlibRect> rectResolver, double mouseX, double mouseY) {
		T selectedTab = selectedTab();
		for (T tab : tabs) {
			renderTab(context, textRenderer, rectResolver.apply(tab), label(tab), Objects.equals(selectedTab, tab), mouseX, mouseY);
		}
	}

	public void renderTransition(DrawContext context, TextRenderer textRenderer, GlibRect content) {
		renderTransition(context, textRenderer, content, "Opening ");
	}

	public void renderTransition(DrawContext context, TextRenderer textRenderer, GlibRect content, String loadingPrefix) {
		loadingScreen.message((loadingPrefix == null ? "" : loadingPrefix) + label(transitionToTab) + "...");
		loadingScreen.render(context, textRenderer, content);
	}

	private void renderTab(DrawContext context, TextRenderer textRenderer, GlibRect rect, String label, boolean selected, double mouseX, double mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		int fill = selected ? 0xE0222B38 : (hovered ? 0xC01C2532 : 0x90161D28);
		int border = selected ? accentColor : (hovered ? GlibUi.withAlpha(accentColor, 0x90) : lineColor);
		context.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), fill);
		context.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + 1, border);
		context.fill(rect.x(), rect.y() + rect.height() - 1, rect.x() + rect.width(), rect.y() + rect.height(), 0x7010161F);
		String trimmed = textRenderer.trimToWidth(label, rect.width() - 14);
		context.drawTextWithShadow(textRenderer, Text.literal(trimmed), rect.x() + (rect.width() - textRenderer.getWidth(trimmed)) / 2, rect.y() + 7, selected ? 0xFFFFFFFF : 0xFFB6C0CF);
	}

	private String label(T tab) {
		return labelResolver.apply(tab);
	}

}
