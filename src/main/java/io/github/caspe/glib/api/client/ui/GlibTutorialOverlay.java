package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Reusable guided tutorial overlay for GLib editors.
 *
 * <p>The overlay owns the dialogue-editor style presentation and motion: delayed
 * fade-in, moving spotlight and card, cross-fading content, typewriter text,
 * interaction status, and Back/Next/Skip controls. Editors only provide target
 * rectangles and decide whether the current step has been completed.</p>
 */
@Environment(EnvType.CLIENT)
public final class GlibTutorialOverlay {
	private static final float START_DELAY_SECONDS = 0.16F;
	private static final float FADE_SECONDS = 0.34F;
	private static final float MOVE_SECONDS = 0.95F;
	private static final float CARD_SECONDS = 0.42F;
	private static final float CHARS_PER_SECOND = 44.0F;
	private static final float CONTENT_SWAP_POINT = 0.5F;
	private static final int DEFAULT_ACCENT = 0xFF45D483;
	private static final int BACK_ACCENT = 0xFFFFC857;
	private static final int SKIP_ACCENT = 0xFF6D7787;
	private static final int LINE = 0x80394655;

	private final List<Step> steps;
	private final int accent;
	private final Runnable finishAction;
	private boolean active;
	private boolean animationStarted;
	private int stepIndex;
	private int pageOffset;
	private long startRequestedAtNanos;
	private long fadeStartedAtNanos;
	private long moveStartedAtNanos;
	private long cardStartedAtNanos;
	private long textStartedAtNanos;
	private GlibRect visualTarget = GlibRect.empty();
	private GlibRect visualPanel = GlibRect.empty();
	private GlibRect moveFromTarget = GlibRect.empty();
	private GlibRect moveFromPanel = GlibRect.empty();
	private GlibRect backRect = GlibRect.empty();
	private GlibRect nextRect = GlibRect.empty();
	private GlibRect skipRect = GlibRect.empty();
	private float backHover;
	private float nextHover;
	private float skipHover;
	private String previousBody = "";
	private String previousTitle = "";
	private String previousPage = "";
	private boolean hasPreviousContent;
	private boolean textRevealed;
	private boolean nextEnabled = true;
	private boolean backEnabled = true;
	private boolean skipEnabled = true;
	private String statusOverride;
	private StepChangedListener stepChangedListener = (index, step) -> {};

	public GlibTutorialOverlay(List<Step> steps, Runnable finishAction) {
		this(steps, DEFAULT_ACCENT, finishAction);
	}

	public GlibTutorialOverlay(List<Step> steps, int accent, Runnable finishAction) {
		this.steps = List.copyOf(steps);
		this.accent = accent;
		this.finishAction = finishAction == null ? () -> {} : finishAction;
	}

	public GlibTutorialOverlay onStepChanged(StepChangedListener listener) {
		stepChangedListener = listener == null ? (index, step) -> {} : listener;
		return this;
	}

	public boolean active() { return active; }
	public int stepIndex() { return stepIndex; }
	public int stepCount() { return steps.size(); }
	public boolean ready() { return active && secondsSince(startRequestedAtNanos) >= START_DELAY_SECONDS; }
	public boolean interactionReady() { return ready() && animationStarted && moveProgress() >= 0.999F && cardProgress() >= 0.999F; }

	public Step currentStep() {
		if (steps.isEmpty()) return Step.empty();
		return steps.get(Math.max(0, Math.min(steps.size() - 1, stepIndex)));
	}

	public String currentTargetKey() {
		return active ? currentStep().targetKey() : "";
	}

	public void setNextEnabled(boolean enabled) { nextEnabled = enabled; }
	public void setBackEnabled(boolean enabled) { backEnabled = enabled; }
	public void setSkipEnabled(boolean enabled) { skipEnabled = enabled; }
	public void setStatus(String status) { statusOverride = status; }

	public void start() { start(0); }

	public void start(int pageOffset) {
		active = !steps.isEmpty();
		animationStarted = false;
		stepIndex = 0;
		this.pageOffset = Math.max(0, pageOffset);
		startRequestedAtNanos = System.nanoTime();
		visualTarget = GlibRect.empty();
		visualPanel = GlibRect.empty();
		moveFromTarget = GlibRect.empty();
		moveFromPanel = GlibRect.empty();
		backRect = nextRect = skipRect = GlibRect.empty();
		backHover = nextHover = skipHover = 0.0F;
		previousBody = previousTitle = previousPage = "";
		hasPreviousContent = false;
		textRevealed = false;
		nextEnabled = backEnabled = skipEnabled = true;
		statusOverride = null;
		if (active) stepChangedListener.changed(stepIndex, currentStep());
	}

	public void stop() {
		active = false;
		backRect = nextRect = skipRect = GlibRect.empty();
	}

	/** Starts the visual animation once the standard dialogue-editor delay has elapsed. */
	public void startFadeIfNeeded() {
		if (!active || !ready() || animationStarted) return;
		animationStarted = true;
		long now = System.nanoTime();
		fadeStartedAtNanos = now;
		moveStartedAtNanos = now;
		cardStartedAtNanos = now;
		textStartedAtNanos = now;
	}

	/** Advances after an editor has completed a required interaction. */
	public void nextStep() {
		if (!active) return;
		if (stepIndex >= steps.size() - 1) finish();
		else changeStep(stepIndex + 1);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!active) return false;
		if (!ready()) return true;
		if (mouseClickedControls(mouseX, mouseY, button)) return true;
		return !(currentStep().allowTargetInteraction() && interactionReady() && visualTarget.contains(mouseX, mouseY));
	}

	public boolean mouseClickedControls(double mouseX, double mouseY, int button) {
		if (!active || !ready()) return false;
		startFadeIfNeeded();
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		if (skipRect.contains(mouseX, mouseY) && skipEnabled) {
			finish();
			return true;
		}
		if (backRect.contains(mouseX, mouseY) && canGoBack()) {
			changeStep(stepIndex - 1);
			return true;
		}
		if (nextRect.contains(mouseX, mouseY) && nextEnabled) {
			if (!textComplete()) textRevealed = true;
			else if (stepIndex >= steps.size() - 1) finish();
			else changeStep(stepIndex + 1);
			return true;
		}
		return false;
	}

	public boolean keyPressed(int keyCode) {
		if (!active) return false;
		if (!ready()) return true;
		startFadeIfNeeded();
		if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN) {
			if (nextEnabled) {
				if (!textComplete()) textRevealed = true;
				else if (stepIndex >= steps.size() - 1) finish();
				else changeStep(stepIndex + 1);
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP) {
			if (canGoBack()) changeStep(stepIndex - 1);
			return true;
		}
		return true;
	}

	public void render(DrawContext context, TextRenderer renderer, int screenWidth, int screenHeight, int mouseX, int mouseY, TargetResolver resolver) {
		if (!active || steps.isEmpty() || !ready()) return;
		startFadeIfNeeded();
		Step step = currentStep();
		GlibRect desiredTarget = resolver.resolve(step.targetKey());
		if (desiredTarget == null || desiredTarget.isEmpty()) desiredTarget = new GlibRect(14, 14, Math.max(1, screenWidth - 28), Math.max(1, screenHeight - 28));
		GlibRect desiredPanel = tutorialPanelRect(desiredTarget, screenWidth, screenHeight);
		float moveProgress = moveProgress();
		GlibRect target = moveFromTarget.isEmpty() ? desiredTarget : moveFromTarget.interpolate(desiredTarget, moveProgress);
		GlibRect panel = moveFromPanel.isEmpty() ? desiredPanel : moveFromPanel.interpolate(desiredPanel, moveProgress);
		visualTarget = target;
		visualPanel = panel;

		float fadeProgress = fadeProgress();
		float cardProgress = cardProgress();
		float outgoingAlpha = !hasPreviousContent || cardProgress >= CONTENT_SWAP_POINT
			? 0.0F : 1.0F - GlibUi.easeInOutCubic(cardProgress / CONTENT_SWAP_POINT);
		float incomingAlpha = !hasPreviousContent
			? cardProgress
			: cardProgress <= CONTENT_SWAP_POINT ? 0.0F : GlibUi.easeInOutCubic((cardProgress - CONTENT_SWAP_POINT) / (1.0F - CONTENT_SWAP_POINT));

		drawScrim(context, target, screenWidth, screenHeight, fadeProgress);
		drawHighlight(context, target, fadeProgress);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), GlibUi.withAlpha(0xFA121720, fadeProgress), GlibUi.withAlpha(LINE, fadeProgress));
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, GlibUi.withAlpha(accent, fadeProgress));

		if (outgoingAlpha > 0.0F) {
			drawHeader(context, renderer, panel, previousTitle, previousPage, fadeProgress * outgoingAlpha);
			drawBody(context, renderer, panel, previousBody, fadeProgress * outgoingAlpha);
		}
		drawHeader(context, renderer, panel, step.title(), page(stepIndex), fadeProgress * incomingAlpha);
		drawBody(context, renderer, panel, visibleBody(step.body()), fadeProgress * incomingAlpha);

		String status = statusOverride == null ? step.status() : statusOverride;
		if (!status.isBlank() && incomingAlpha > 0.0F) {
			int alpha = textAlpha(fadeProgress * incomingAlpha);
			if (alpha >= 4) {
				String trimmed = renderer.trimToWidth(status, panel.width() - 24);
				context.drawTextWithShadow(renderer, Text.literal(trimmed), panel.x() + 12, panel.y() + 84, GlibUi.withAlpha(accent, alpha));
			}
		}

		int buttonY = panel.y() + panel.height() - 28;
		backRect = new GlibRect(panel.x() + 12, buttonY, 52, 18);
		nextRect = new GlibRect(panel.x() + 70, buttonY, 60, 18);
		skipRect = new GlibRect(panel.x() + panel.width() - 54, buttonY, 42, 18);
		boolean canBack = canGoBack();
		backHover = GlibUi.smoothHover(backHover, canBack && backRect.contains(mouseX, mouseY));
		nextHover = GlibUi.smoothHover(nextHover, nextEnabled && nextRect.contains(mouseX, mouseY));
		skipHover = GlibUi.smoothHover(skipHover, skipEnabled && skipRect.contains(mouseX, mouseY));
		drawButton(context, renderer, backRect, "Back", canBack, BACK_ACCENT, backHover, fadeProgress);
		drawButton(context, renderer, nextRect, stepIndex == steps.size() - 1 ? "Finish" : "Next", nextEnabled, accent, nextHover, fadeProgress);
		drawButton(context, renderer, skipRect, "Skip", skipEnabled, SKIP_ACCENT, skipHover, fadeProgress);
	}

	private boolean canGoBack() { return backEnabled && stepIndex > 0; }

	private void finish() {
		stop();
		finishAction.run();
	}

	private void changeStep(int requested) {
		int next = Math.max(0, Math.min(steps.size() - 1, requested));
		if (!active || next == stepIndex) return;
		Step previous = currentStep();
		previousBody = visibleBody(previous.body());
		previousTitle = previous.title();
		previousPage = page(stepIndex);
		hasPreviousContent = true;
		moveFromTarget = visualTarget;
		moveFromPanel = visualPanel;
		stepIndex = next;
		long now = System.nanoTime();
		moveStartedAtNanos = now;
		cardStartedAtNanos = now;
		textStartedAtNanos = now;
		textRevealed = false;
		nextEnabled = backEnabled = skipEnabled = true;
		statusOverride = null;
		stepChangedListener.changed(stepIndex, currentStep());
	}

	private String page(int index) { return (pageOffset + index + 1) + " / " + (pageOffset + steps.size()); }

	private void drawHeader(DrawContext context, TextRenderer renderer, GlibRect panel, String titleText, String page, float alpha) {
		int textAlpha = textAlpha(alpha);
		if (textAlpha < 4) return;
		String title = renderer.trimToWidth(titleText, Math.max(80, panel.width() - renderer.getWidth(page) - 38));
		context.drawTextWithShadow(renderer, Text.literal(title), panel.x() + 12, panel.y() + 12, GlibUi.withAlpha(0xFFFFFFFF, textAlpha));
		context.drawTextWithShadow(renderer, Text.literal(page), panel.x() + panel.width() - renderer.getWidth(page) - 12, panel.y() + 12, GlibUi.withAlpha(0xFF8E98A6, textAlpha));
	}

	private void drawBody(DrawContext context, TextRenderer renderer, GlibRect panel, String body, float alpha) {
		int textAlpha = textAlpha(alpha);
		if (textAlpha < 4 || body.isEmpty()) return;
		List<OrderedText> lines = renderer.wrapLines(Text.literal(body), panel.width() - 24);
		for (int index = 0; index < Math.min(4, lines.size()); index++) {
			context.drawTextWithShadow(renderer, lines.get(index), panel.x() + 12, panel.y() + 32 + index * 12, GlibUi.withAlpha(0xFFC9D4E5, textAlpha));
		}
	}

	private void drawButton(DrawContext context, TextRenderer renderer, GlibRect rect, String label, boolean enabled, int buttonAccent, float hover, float alpha) {
		int baseFill = enabled ? 0xE0222B38 : 0x80313A48;
		int fill = GlibUi.mixColor(baseFill, 0xF0384658, hover);
		int border = enabled ? GlibUi.mixColor(buttonAccent, 0xFFFFFFFF, hover * 0.22F) : LINE;
		context.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), GlibUi.withAlpha(fill, alpha));
		context.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + 1, GlibUi.withAlpha(border, alpha));
		if (enabled && hover > 0.02F) context.fill(rect.x(), rect.y(), rect.x() + 1, rect.y() + rect.height(), GlibUi.withAlpha(buttonAccent, alpha * hover));
		String trimmed = renderer.trimToWidth(label, rect.width() - 8);
		int textColor = enabled ? GlibUi.mixColor(0xFFD6DEE9, 0xFFFFFFFF, hover) : 0xFF6D7787;
		int textAlpha = textAlpha(alpha);
		if (textAlpha >= 4) context.drawTextWithShadow(renderer, Text.literal(trimmed), rect.x() + (rect.width() - renderer.getWidth(trimmed)) / 2, rect.y() + 5, GlibUi.withAlpha(textColor, textAlpha));
	}

	private void drawScrim(DrawContext context, GlibRect target, int width, int height, float alpha) {
		int scrim = GlibUi.withAlpha(0xB8000000, alpha);
		int left = Math.max(0, target.x() - 4);
		int top = Math.max(0, target.y() - 4);
		int right = Math.min(width, target.x() + target.width() + 4);
		int bottom = Math.min(height, target.y() + target.height() + 4);
		context.fill(0, 0, width, top, scrim);
		context.fill(0, top, left, bottom, scrim);
		context.fill(right, top, width, bottom, scrim);
		context.fill(0, bottom, width, height, scrim);
	}

	private void drawHighlight(DrawContext context, GlibRect target, float alpha) {
		int x1 = target.x() - 2;
		int y1 = target.y() - 2;
		int x2 = target.x() + target.width() + 2;
		int y2 = target.y() + target.height() + 2;
		int color = GlibUi.withAlpha(accent, alpha);
		context.fill(x1, y1, x2, y1 + 2, color);
		context.fill(x1, y2 - 2, x2, y2, color);
		context.fill(x1, y1, x1 + 2, y2, color);
		context.fill(x2 - 2, y1, x2, y2, color);
	}

	private GlibRect tutorialPanelRect(GlibRect target, int width, int height) {
		int preferredWidth = Math.min(344, width - 28);
		int availableLeft = target.x() - 28;
		int panelWidth = availableLeft >= 210 ? Math.min(preferredWidth, availableLeft) : preferredWidth;
		int panelHeight = 136;
		int rightX = target.x() + target.width() + 14;
		int leftX = target.x() - panelWidth - 14;
		boolean beside = rightX + panelWidth <= width - 14 || leftX >= 14;
		int panelX = rightX + panelWidth <= width - 14 ? rightX : leftX >= 14 ? leftX : Math.max(14, Math.min(width - panelWidth - 14, target.x()));
		int belowY = target.y() + target.height() + 14;
		int panelY = beside ? Math.max(14, Math.min(height - panelHeight - 14, target.y()))
			: belowY + panelHeight <= height - 14 ? belowY : Math.max(14, target.y() - panelHeight - 14);
		return new GlibRect(panelX, panelY, panelWidth, panelHeight);
	}

	private String visibleBody(String body) {
		if (textRevealed) return body;
		float delay = hasPreviousContent ? CARD_SECONDS * CONTENT_SWAP_POINT : 0.0F;
		float typingSeconds = Math.max(0.0F, secondsSince(textStartedAtNanos) - delay);
		int visible = Math.min(body.length(), (int) Math.floor(typingSeconds * CHARS_PER_SECOND));
		return body.substring(0, visible);
	}

	private boolean textComplete() {
		if (textRevealed) return true;
		float delay = hasPreviousContent ? CARD_SECONDS * CONTENT_SWAP_POINT : 0.0F;
		return Math.max(0.0F, secondsSince(textStartedAtNanos) - delay) * CHARS_PER_SECOND >= currentStep().body().length();
	}

	private float fadeProgress() { return GlibUi.easeOutCubic(Math.min(1.0F, secondsSince(fadeStartedAtNanos) / FADE_SECONDS)); }
	private float moveProgress() { return moveFromTarget.isEmpty() || moveFromPanel.isEmpty() ? 1.0F : GlibUi.easeInOutCubic(Math.min(1.0F, secondsSince(moveStartedAtNanos) / MOVE_SECONDS)); }
	private float cardProgress() { return GlibUi.easeInOutCubic(Math.min(1.0F, secondsSince(cardStartedAtNanos) / CARD_SECONDS)); }
	private float secondsSince(long startedAt) { return Math.max(0.0F, (System.nanoTime() - startedAt) / 1_000_000_000.0F); }
	private int textAlpha(float alpha) { return Math.max(0, Math.min(255, Math.round(alpha * 255.0F))); }

	public record Step(String title, String body, String targetKey, String status, boolean allowTargetInteraction) {
		public Step(String title, String body, String targetKey) { this(title, body, targetKey, "", false); }
		public Step(String title, String body, String targetKey, String status) { this(title, body, targetKey, status, false); }
		public Step {
			title = title == null ? "" : title;
			body = body == null ? "" : body;
			targetKey = targetKey == null ? "" : targetKey;
			status = status == null ? "" : status;
		}
		private static Step empty() { return new Step("", "", ""); }
	}

	@FunctionalInterface
	public interface TargetResolver { GlibRect resolve(String targetKey); }

	@FunctionalInterface
	public interface StepChangedListener { void changed(int index, Step step); }
}
