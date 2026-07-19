package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Reusable loading transition for an editor region or a complete screen.
 *
 * <p>The transition fades existing content to an opaque loading surface, holds a centered
 * message and animated spinner, exposes a commit point for swapping content, and then fades
 * the new content back in. Hosts can use the lifecycle directly or use {@link GlibEditorTabs},
 * which coordinates it automatically for tab changes.</p>
 */
@Environment(EnvType.CLIENT)
public final class GlibLoadingScreen {
	public static final float DEFAULT_FADE_SECONDS = 0.28F;
	public static final float DEFAULT_LOADING_SECONDS = 2.0F;
	private static final int DEFAULT_BACKGROUND = 0xFF101722;

	private final int accentColor;
	private final int backgroundColor;
	private final float fadeSeconds;
	private final float defaultLoadingSeconds;
	private String message = "Loading...";
	private float loadingSeconds;
	private long startedAtNanos;
	private boolean active;

	public GlibLoadingScreen(int accentColor) {
		this(accentColor, DEFAULT_BACKGROUND, DEFAULT_FADE_SECONDS, DEFAULT_LOADING_SECONDS);
	}

	public GlibLoadingScreen(int accentColor, int backgroundColor, float fadeSeconds, float loadingSeconds) {
		this.accentColor = accentColor;
		this.backgroundColor = backgroundColor;
		this.fadeSeconds = Math.max(0.0F, fadeSeconds);
		this.defaultLoadingSeconds = Math.max(0.0F, loadingSeconds);
		this.loadingSeconds = this.defaultLoadingSeconds;
	}

	public void start(String message) {
		start(message, defaultLoadingSeconds);
	}

	public void start(String message, float loadingSeconds) {
		message(message);
		this.loadingSeconds = Math.max(0.0F, loadingSeconds);
		this.startedAtNanos = System.nanoTime();
		this.active = true;
	}

	public void message(String message) {
		this.message = message == null || message.isBlank() ? "Loading..." : message;
	}

	public void cancel() {
		active = false;
	}

	/**
	 * Moves an active transition into its final fade. This is useful for asynchronous work:
	 * start with a long loading duration, then finish as soon as the real operation completes.
	 */
	public void finish() {
		if (!active) return;
		float fadeOutStart = fadeSeconds + loadingSeconds;
		float elapsed = elapsedSeconds();
		if (elapsed < fadeOutStart) {
			startedAtNanos -= (long) ((fadeOutStart - elapsed) * 1_000_000_000.0F);
		}
	}

	public boolean active() {
		return active;
	}

	public boolean showingLoading() {
		float elapsed = elapsedSeconds();
		return active && elapsed >= fadeSeconds && elapsed < fadeSeconds + loadingSeconds;
	}

	public boolean hidesWidgets() {
		return active && elapsedSeconds() >= fadeSeconds && !commitReady();
	}

	public boolean commitReady() {
		return active && elapsedSeconds() >= fadeSeconds + loadingSeconds;
	}

	public boolean finished() {
		return active && elapsedSeconds() >= totalSeconds();
	}

	public float elapsedSeconds() {
		return active ? (System.nanoTime() - startedAtNanos) / 1_000_000_000.0F : 0.0F;
	}

	public float loadingSeconds() {
		return loadingSeconds;
	}

	public float totalSeconds() {
		return fadeSeconds + loadingSeconds + fadeSeconds;
	}

	public void render(DrawContext context, TextRenderer textRenderer, GlibRect content) {
		if (!active || content == null || content.isEmpty()) return;
		float elapsed = elapsedSeconds();
		float overlayAlpha;
		boolean loading = false;
		if (elapsed < fadeSeconds) {
			overlayAlpha = fadeSeconds <= 0.0F ? 1.0F : GlibUi.easeInOutCubic(elapsed / fadeSeconds);
		} else if (elapsed < fadeSeconds + loadingSeconds) {
			overlayAlpha = 1.0F;
			loading = true;
		} else {
			float fadeInElapsed = elapsed - fadeSeconds - loadingSeconds;
			overlayAlpha = fadeSeconds <= 0.0F ? 0.0F : 1.0F - GlibUi.easeInOutCubic(fadeInElapsed / fadeSeconds);
		}
		context.fill(content.x(), content.y(), content.x() + content.width(), content.y() + content.height(),
			loading ? backgroundColor : GlibUi.withAlpha(backgroundColor, overlayAlpha));
		if (loading) drawLoading(context, textRenderer, content, elapsed - fadeSeconds);
	}

	private void drawLoading(DrawContext context, TextRenderer textRenderer, GlibRect content, float loadingElapsed) {
		float fadeWindow = Math.min(0.18F, loadingSeconds * 0.35F);
		float alpha = fadeWindow <= 0.0F ? 1.0F
			: Math.min(1.0F, Math.min(loadingElapsed / fadeWindow, (loadingSeconds - loadingElapsed) / fadeWindow));
		if (alpha <= 0.0F) return;
		int centerX = content.x() + content.width() / 2;
		int centerY = content.y() + content.height() / 2 + 10;
		String trimmed = textRenderer.trimToWidth(message, Math.max(40, content.width() - 40));
		context.drawTextWithShadow(textRenderer, Text.literal(trimmed), centerX - textRenderer.getWidth(trimmed) / 2,
			centerY - 38, GlibUi.withAlpha(0xFFFFFFFF, alpha));
		drawSpinner(context, centerX, centerY, loadingElapsed, alpha);
	}

	private void drawSpinner(DrawContext context, int centerX, int centerY, float seconds, float alpha) {
		int dots = 12;
		int radius = 16;
		double rotation = seconds * Math.PI * 2.0D;
		for (int index = 0; index < dots; index++) {
			double angle = rotation + index * Math.PI * 2.0D / dots;
			int x = centerX + (int) Math.round(Math.cos(angle) * radius);
			int y = centerY + (int) Math.round(Math.sin(angle) * radius);
			float dotAlpha = alpha * (0.22F + 0.78F * (index + 1) / dots);
			int size = index > dots - 4 ? 3 : 2;
			context.fill(x - size / 2, y - size / 2, x - size / 2 + size, y - size / 2 + size,
				GlibUi.withAlpha(accentColor, dotAlpha));
		}
	}
}
