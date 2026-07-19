package io.github.caspe.glib.api.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibScreenAnimator;
import io.github.caspe.glib.api.client.ui.GlibUi;
import io.github.caspe.glib.api.config.GlibConfigScreen;
import io.github.caspe.glib.client.social.GlibSocialScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public final class GlibMenuScreen extends Screen {
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xD0161D28;
	private static final int LINE = 0x80394655;
	private static final int ACCENT = 0xFF45D483;
	private static final int ACCENT_WARN = 0xFFFFC857;
	private static final int LOGO_WIDTH = 540;
	private static final int LOGO_HEIGHT = 90;
	private static final float LOGO_FLY_IN_SECONDS = 0.52F;
	private static final float LOGO_FLY_STAGGER_SECONDS = 0.055F;
	private static final float LOGO_FLY_TOTAL_SECONDS = LOGO_FLY_IN_SECONDS + LOGO_FLY_STAGGER_SECONDS * 8.0F;
	private static final float LOGO_HOLD_SECONDS = 0.20F;
	private static final float LOGO_MOVE_SECONDS = 0.85F;
	private static final float LOGO_MOVE_START_SECONDS = LOGO_FLY_TOTAL_SECONDS + LOGO_HOLD_SECONDS;
	private static final float TYPE_START_SECONDS = LOGO_MOVE_START_SECONDS + LOGO_MOVE_SECONDS;
	private static final float TYPE_CHARS_PER_SECOND = 30.0F;
	private static final float BUTTON_FADE_SECONDS = 0.70F;
	private static final float LOOP_TRACE_SECONDS = 1.55F;
	private static final float LOOP_FADE_IN_SECONDS = 0.22F;
	private static final float LOOP_HOLD_SECONDS = 0.15F;
	private static final float LOOP_FADE_OUT_SECONDS = 0.45F;
	private static final int[] LOGO_RENDER_ORDER = {0, 1, 2, 3, 4, 5, 8, 7, 6};
	private static final List<LogoTracePoint> LOGO_TRACE = List.of(
		new LogoTracePoint(16, 90),
		new LogoTracePoint(73, 90),
		new LogoTracePoint(73, 84),
		new LogoTracePoint(74, 84),
		new LogoTracePoint(74, 78),
		new LogoTracePoint(75, 78),
		new LogoTracePoint(75, 71),
		new LogoTracePoint(76, 71),
		new LogoTracePoint(76, 65),
		new LogoTracePoint(77, 65),
		new LogoTracePoint(77, 58),
		new LogoTracePoint(78, 58),
		new LogoTracePoint(79, 48),
		new LogoTracePoint(80, 48),
		new LogoTracePoint(80, 50),
		new LogoTracePoint(83, 51),
		new LogoTracePoint(84, 55),
		new LogoTracePoint(92, 55),
		new LogoTracePoint(90, 69),
		new LogoTracePoint(91, 69),
		new LogoTracePoint(91, 71),
		new LogoTracePoint(92, 71),
		new LogoTracePoint(92, 73),
		new LogoTracePoint(93, 73),
		new LogoTracePoint(93, 75),
		new LogoTracePoint(94, 75),
		new LogoTracePoint(94, 77),
		new LogoTracePoint(95, 77),
		new LogoTracePoint(95, 79),
		new LogoTracePoint(96, 79),
		new LogoTracePoint(96, 81),
		new LogoTracePoint(97, 81),
		new LogoTracePoint(97, 83),
		new LogoTracePoint(98, 83),
		new LogoTracePoint(98, 85),
		new LogoTracePoint(99, 85),
		new LogoTracePoint(101, 90),
		new LogoTracePoint(129, 90),
		new LogoTracePoint(130, 89),
		new LogoTracePoint(130, 81),
		new LogoTracePoint(131, 81),
		new LogoTracePoint(131, 79),
		new LogoTracePoint(133, 81),
		new LogoTracePoint(134, 87),
		new LogoTracePoint(135, 87),
		new LogoTracePoint(136, 90),
		new LogoTracePoint(219, 90),
		new LogoTracePoint(219, 79),
		new LogoTracePoint(220, 79),
		new LogoTracePoint(219, 66),
		new LogoTracePoint(220, 66),
		new LogoTracePoint(220, 55),
		new LogoTracePoint(221, 55),
		new LogoTracePoint(221, 46),
		new LogoTracePoint(220, 46),
		new LogoTracePoint(220, 42),
		new LogoTracePoint(221, 42),
		new LogoTracePoint(221, 28),
		new LogoTracePoint(222, 28),
		new LogoTracePoint(223, 43),
		new LogoTracePoint(234, 43),
		new LogoTracePoint(233, 72),
		new LogoTracePoint(234, 72),
		new LogoTracePoint(235, 90),
		new LogoTracePoint(263, 90),
		new LogoTracePoint(264, 43),
		new LogoTracePoint(277, 43),
		new LogoTracePoint(278, 78),
		new LogoTracePoint(285, 78),
		new LogoTracePoint(285, 90),
		new LogoTracePoint(313, 90),
		new LogoTracePoint(313, 82),
		new LogoTracePoint(314, 82),
		new LogoTracePoint(315, 72),
		new LogoTracePoint(319, 72),
		new LogoTracePoint(319, 90),
		new LogoTracePoint(347, 90),
		new LogoTracePoint(350, 78),
		new LogoTracePoint(354, 77),
		new LogoTracePoint(355, 69),
		new LogoTracePoint(356, 69),
		new LogoTracePoint(356, 73),
		new LogoTracePoint(357, 73),
		new LogoTracePoint(357, 87),
		new LogoTracePoint(358, 87),
		new LogoTracePoint(358, 90),
		new LogoTracePoint(525, 90),
		new LogoTracePoint(526, 87),
		new LogoTracePoint(532, 81),
		new LogoTracePoint(532, 79),
		new LogoTracePoint(535, 77),
		new LogoTracePoint(535, 75),
		new LogoTracePoint(540, 70),
		new LogoTracePoint(540, 63),
		new LogoTracePoint(539, 63),
		new LogoTracePoint(539, 59),
		new LogoTracePoint(538, 59),
		new LogoTracePoint(537, 49),
		new LogoTracePoint(536, 49),
		new LogoTracePoint(536, 45),
		new LogoTracePoint(535, 45),
		new LogoTracePoint(535, 40),
		new LogoTracePoint(534, 40),
		new LogoTracePoint(534, 36),
		new LogoTracePoint(533, 36),
		new LogoTracePoint(533, 31),
		new LogoTracePoint(532, 31),
		new LogoTracePoint(532, 26),
		new LogoTracePoint(531, 26),
		new LogoTracePoint(531, 22),
		new LogoTracePoint(530, 22),
		new LogoTracePoint(530, 17),
		new LogoTracePoint(529, 17),
		new LogoTracePoint(529, 13),
		new LogoTracePoint(528, 13),
		new LogoTracePoint(528, 8),
		new LogoTracePoint(527, 8),
		new LogoTracePoint(526, 0),
		new LogoTracePoint(358, 0),
		new LogoTracePoint(356, 11),
		new LogoTracePoint(355, 11),
		new LogoTracePoint(355, 0),
		new LogoTracePoint(327, 0),
		new LogoTracePoint(326, 1),
		new LogoTracePoint(326, 9),
		new LogoTracePoint(325, 9),
		new LogoTracePoint(325, 16),
		new LogoTracePoint(324, 16),
		new LogoTracePoint(324, 18),
		new LogoTracePoint(306, 18),
		new LogoTracePoint(306, 0),
		new LogoTracePoint(221, 0),
		new LogoTracePoint(221, 12),
		new LogoTracePoint(220, 12),
		new LogoTracePoint(220, 7),
		new LogoTracePoint(219, 7),
		new LogoTracePoint(219, 0),
		new LogoTracePoint(135, 0),
		new LogoTracePoint(134, 12),
		new LogoTracePoint(132, 12),
		new LogoTracePoint(132, 10),
		new LogoTracePoint(131, 10),
		new LogoTracePoint(131, 6),
		new LogoTracePoint(129, 4),
		new LogoTracePoint(129, 0),
		new LogoTracePoint(100, 0),
		new LogoTracePoint(100, 3),
		new LogoTracePoint(99, 3),
		new LogoTracePoint(99, 0),
		new LogoTracePoint(73, 0),
		new LogoTracePoint(73, 3),
		new LogoTracePoint(72, 3),
		new LogoTracePoint(71, 0),
		new LogoTracePoint(15, 0),
		new LogoTracePoint(14, 8),
		new LogoTracePoint(13, 8),
		new LogoTracePoint(13, 13),
		new LogoTracePoint(12, 13),
		new LogoTracePoint(12, 17),
		new LogoTracePoint(11, 17),
		new LogoTracePoint(11, 22),
		new LogoTracePoint(10, 22),
		new LogoTracePoint(10, 26),
		new LogoTracePoint(9, 26),
		new LogoTracePoint(9, 31),
		new LogoTracePoint(8, 31),
		new LogoTracePoint(8, 36),
		new LogoTracePoint(7, 36),
		new LogoTracePoint(7, 40),
		new LogoTracePoint(6, 40),
		new LogoTracePoint(6, 45),
		new LogoTracePoint(5, 45),
		new LogoTracePoint(3, 59),
		new LogoTracePoint(2, 59),
		new LogoTracePoint(2, 63),
		new LogoTracePoint(1, 63),
		new LogoTracePoint(0, 69),
		new LogoTracePoint(3, 71),
		new LogoTracePoint(3, 73),
		new LogoTracePoint(6, 75),
		new LogoTracePoint(6, 77),
		new LogoTracePoint(9, 79),
		new LogoTracePoint(9, 81),
		new LogoTracePoint(15, 87),
		new LogoTracePoint(16, 90)
	);
	private static final float LOGO_TRACE_LENGTH = logoTraceLength();
	private static final List<LogoPiece> LOGO_PIECES = List.of(
		new LogoPiece(logoTexture("g"), 82, 90, 0, 0, -160, -90),
		new LogoPiece(logoTexture("apostrophe"), 38, 55, 68, 0, 110, -120),
		new LogoPiece(logoTexture("n"), 79, 90, 90, 0, -110, 100),
		new LogoPiece(logoTexture("e"), 65, 90, 156, 0, 140, -85),
		new LogoPiece(logoTexture("t"), 57, 90, 220, 0, -70, 120),
		new LogoPiece(logoTexture("w"), 81, 90, 277, 0, 100, 90),
		new LogoPiece(logoTexture("o"), 69, 90, 353, 0, -120, -75),
		new LogoPiece(logoTexture("r"), 76, 90, 406, 0, 120, 100),
		new LogoPiece(logoTexture("k"), 82, 90, 458, 0, 170, -80)
	);

	private final GlibScreenAnimator screenAnimator = new GlibScreenAnimator(0.34F, 0.26F, 24.0D, 64.0D);
	private final Screen parent;
	private final Text subtitle;
	private final List<GlibMenuAction> actions;
	private final boolean animateOpen;
	private final boolean rootMenu;
	private final Text settingsTooltip;
	private final GlibMenuEntry.ScreenFactory settingsScreenFactory;
	private final List<GlibButton> actionButtons = new ArrayList<>();
	private GlibButton backButton;
	private GlibButton settingsButton;
	private GlibButton friendsButton;
	private double lastOpenOffset;

	public GlibMenuScreen(Screen parent) {
		this(
			parent,
			Text.literal("G'Network"),
			Text.literal("Choose a workspace."),
			rootActions(),
			true,
			true,
			Text.literal("G'Lib Settings"),
			GlibMenuScreen::glibSettingsScreen
		);
	}

	public GlibMenuScreen(Screen parent, Text title, Text subtitle, List<GlibMenuAction> actions) {
		this(parent, title, subtitle, actions, false, false, null, null);
	}

	private GlibMenuScreen(
		Screen parent,
		Text title,
		Text subtitle,
		List<GlibMenuAction> actions,
		boolean animateOpen,
		boolean rootMenu,
		Text settingsTooltip,
		GlibMenuEntry.ScreenFactory settingsScreenFactory
	) {
		super(title);
		this.parent = parent;
		this.subtitle = subtitle == null ? Text.empty() : subtitle;
		this.actions = List.copyOf(actions);
		this.animateOpen = animateOpen;
		this.rootMenu = rootMenu;
		this.settingsTooltip = settingsTooltip;
		this.settingsScreenFactory = settingsScreenFactory;
	}

	public static GlibMenuScreen submenu(Screen parent, Text title, Text subtitle, List<GlibMenuAction> actions) {
		return new GlibMenuScreen(parent, title, subtitle, actions, false, false, null, null);
	}

	public static GlibMenuScreen submenu(
		Screen parent,
		Text title,
		Text subtitle,
		List<GlibMenuAction> actions,
		Text settingsTooltip,
		GlibMenuEntry.ScreenFactory settingsScreenFactory
	) {
		return new GlibMenuScreen(parent, title, subtitle, actions, false, false, settingsTooltip, settingsScreenFactory);
	}

	@Override
	protected void init() {
		actionButtons.clear();
		GlibRect panel = panelRect();
		int columns = rootMenu ? Math.min(2, Math.max(1, actions.size())) : actions.size() <= 3 ? 1 : 2;
		int columnGap = 12;
		int rowGap = 10;
		int buttonWidth = rootMenu ? Math.min(220, panel.width() - 64) : columns == 1 ? panel.width() - 64 : (panel.width() - 64 - columnGap) / 2;
		int y = panel.y() + (rootMenu ? 198 : 96);
		for (int index = 0; index < actions.size(); index++) {
			GlibMenuAction action = actions.get(index);
			int column = columns == 1 ? 0 : index % columns;
			int row = columns == 1 ? index : index / columns;
			int rowStart = row * columns;
			int countInRow = Math.min(columns, actions.size() - rowStart);
			int rowWidth = countInRow * buttonWidth + Math.max(0, countInRow - 1) * columnGap;
			int rowX = rootMenu ? panel.x() + (panel.width() - rowWidth) / 2 : panel.x() + 32;
			GlibButton button = new GlibButton(rowX + column * (buttonWidth + columnGap), y + row * (26 + rowGap), buttonWidth, 26, action.label(), action.accentColor(), action.action());
			actionButtons.add(button);
			addDrawableChild(button);
		}
		boolean hasSettings = settingsScreenFactory != null;
		int backWidth = rootMenu || hasSettings ? Math.min(180, panel.width() - 94) : panel.width() - 64;
		int backX = rootMenu || hasSettings ? panel.x() + (panel.width() - backWidth) / 2 : panel.x() + 32;
		backButton = new GlibButton(backX, panel.y() + panel.height() - (rootMenu ? 52 : 42), backWidth, 22, parent == null ? Text.literal("Back to Game") : Text.literal("Back"), ACCENT_WARN, this::requestClose);
		addDrawableChild(backButton);
		if (hasSettings) {
			settingsButton = new GlibButton(panel.x() + panel.width() - 54, panel.y() + panel.height() - (rootMenu ? 52 : 42), 22, 22, Text.literal("\u2699"), 0xFF60A5FA, this::openSettings);
			addDrawableChild(settingsButton);
		} else {
			settingsButton = null;
		}
		if (rootMenu) {
			friendsButton = new GlibButton(panel.x() + panel.width() - 82, panel.y() + panel.height() - 52, 22, 22, Text.literal("F"), ACCENT, this::openFriends);
			addDrawableChild(friendsButton);
		} else {
			friendsButton = null;
		}
		updateRootControls(rootControlsAlpha());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		double offset = screenAnimator.closing() ? screenAnimator.verticalOffset(height) : 0.0D;
		lastOpenOffset = offset;
		int adjustedMouseY = mouseY - (int) Math.round(offset);
		context.getMatrices().push();
		context.getMatrices().translate(0.0F, (float) offset, 0.0F);
		renderPanel(context);
		updateRootControls(rootControlsAlpha());
		super.render(context, mouseX, adjustedMouseY, delta);
		if (settingsButton != null && settingsButton.active && settingsButton.isHovered() && settingsTooltip != null) {
			context.drawTooltip(textRenderer, settingsTooltip, mouseX, adjustedMouseY);
		}
		if (friendsButton != null && friendsButton.active && friendsButton.isHovered()) {
			context.drawTooltip(textRenderer, Text.literal("Friends & Chat"), mouseX, adjustedMouseY);
		}
		context.getMatrices().pop();
		if (screenAnimator.closeComplete() && client != null) {
			client.setScreen(parent);
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return super.mouseClicked(mouseX, mouseY - lastOpenOffset, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return super.mouseReleased(mouseX, mouseY - lastOpenOffset, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			requestClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		requestClose();
	}

	public void requestClose() {
		if (!animateOpen && client != null) {
			client.setScreen(parent);
			return;
		}
		screenAnimator.requestClose();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void renderPanel(DrawContext context) {
		GlibRect panel = panelRect();
		context.fill(0, 0, width, height, 0x52060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);
		int contentTop = panel.y() + (rootMenu ? 170 : 72);
		float controlsAlpha = rootMenu ? rootControlsAlpha() : 1.0F;
		context.fill(panel.x() + 22, contentTop, panel.x() + panel.width() - 22, panel.y() + panel.height() - (rootMenu ? 72 : 58), GlibUi.withAlpha(PANEL_SOFT, controlsAlpha));
		if (rootMenu) {
			renderRootBranding(context, panel);
			renderWelcome(context, panel);
			if (actions.isEmpty()) {
				int emptyAlpha = Math.round(controlsAlpha * 255.0F);
				if (emptyAlpha >= 4) {
					context.drawCenteredTextWithShadow(textRenderer, Text.literal("No G'Network workspaces are installed."), panel.x() + panel.width() / 2, contentTop + 22, GlibUi.withAlpha(0xFF9EA7B3, emptyAlpha));
				}
			}
		} else {
			context.drawTextWithShadow(textRenderer, title, panel.x() + 32, panel.y() + 28, 0xFFFFFFFF);
			context.drawTextWithShadow(textRenderer, subtitle, panel.x() + 32, panel.y() + 45, 0xFF9EA7B3);
		}
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(rootMenu ? 640 : 520, width - 28);
		int rows = rootMenu ? (actions.size() + 1) / 2 : actions.size() <= 3 ? actions.size() : (actions.size() + 1) / 2;
		int desiredHeight = rootMenu ? 352 + Math.max(0, rows - 1) * 40 : Math.max(220, 148 + rows * 36);
		int panelHeight = Math.min(desiredHeight, height - 28);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private void renderRootBranding(DrawContext context, GlibRect panel) {
		float logoScale = Math.min(1.0F, (panel.width() - 64) / (float) LOGO_WIDTH);
		float logoX = panel.x() + (panel.width() - LOGO_WIDTH * logoScale) * 0.5F;
		float elapsed = screenAnimator.secondsSinceOpen();
		float moveProgress = GlibUi.easeInOutCubic(clamp01((elapsed - LOGO_MOVE_START_SECONDS) / LOGO_MOVE_SECONDS));
		float initialY = panel.y() + (panel.height() - LOGO_HEIGHT * logoScale) * 0.5F;
		float finalY = panel.y() + 30.0F;
		float logoY = GlibUi.lerp(initialY, finalY, moveProgress);
		context.enableScissor(panel.x() + 2, panel.y() + 2, panel.x() + panel.width() - 2, panel.y() + panel.height() - 2);
		context.getMatrices().push();
		context.getMatrices().translate(logoX, logoY, 0.0F);
		context.getMatrices().scale(logoScale, logoScale, 1.0F);
		RenderSystem.enableBlend();
		for (int pieceIndex : LOGO_RENDER_ORDER) {
			LogoPiece piece = LOGO_PIECES.get(pieceIndex);
			float flyProgress = GlibUi.easeOutCubic(clamp01((elapsed - pieceIndex * LOGO_FLY_STAGGER_SECONDS) / LOGO_FLY_IN_SECONDS));
			if (flyProgress <= 0.0F) {
				continue;
			}
			context.getMatrices().push();
			context.getMatrices().translate(piece.flyX() * (1.0F - flyProgress), piece.flyY() * (1.0F - flyProgress), 0.0F);
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, flyProgress);
			context.drawTexture(piece.texture(), piece.targetX(), piece.targetY(), 0.0F, 0.0F, piece.width(), piece.height(), piece.width(), piece.height());
			context.getMatrices().pop();
		}
		drawLogoTrace(context, elapsed - TYPE_START_SECONDS);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		context.getMatrices().pop();
		context.disableScissor();
	}

	private void renderWelcome(DrawContext context, GlibRect panel) {
		float typingSeconds = screenAnimator.secondsSinceOpen() - TYPE_START_SECONDS;
		if (typingSeconds < 0.0F) {
			return;
		}
		String username = MinecraftClient.getInstance().getSession().getUsername();
		String welcome = "Welcome " + username + " to G'Network";
		int visibleCharacters = Math.min(welcome.length(), Math.max(0, (int) Math.floor(typingSeconds * TYPE_CHARS_PER_SECOND)));
		String visible = welcome.substring(0, visibleCharacters);
		if (visibleCharacters < welcome.length() && ((int) (typingSeconds * 5.0F) & 1) == 0) {
			visible += "_";
		}
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(visible), panel.x() + panel.width() / 2, panel.y() + 144, 0xFFBFEFFF);
	}

	private void drawLogoTrace(DrawContext context, float localSeconds) {
		float fadeOutStart = LOOP_TRACE_SECONDS + LOOP_HOLD_SECONDS;
		float finishedAt = fadeOutStart + LOOP_FADE_OUT_SECONDS;
		if (localSeconds < 0.0F || localSeconds >= finishedAt) {
			return;
		}
		float fadeIn = GlibUi.easeOutCubic(clamp01(localSeconds / LOOP_FADE_IN_SECONDS));
		float fadeOut = localSeconds <= fadeOutStart
			? 1.0F
			: 1.0F - GlibUi.easeInOutCubic(clamp01((localSeconds - fadeOutStart) / LOOP_FADE_OUT_SECONDS));
		int alpha = Math.round(fadeIn * fadeOut * 255.0F);
		if (alpha < 4) {
			return;
		}
		float progress = GlibUi.easeInOutCubic(clamp01(localSeconds / LOOP_TRACE_SECONDS));
		float remaining = LOGO_TRACE_LENGTH * progress;
		int color = GlibUi.withAlpha(0xFF16D9FF, alpha);
		for (int index = 1; index < LOGO_TRACE.size() && remaining > 0.0F; index++) {
			LogoTracePoint from = LOGO_TRACE.get(index - 1);
			LogoTracePoint to = LOGO_TRACE.get(index);
			float length = (float) Math.hypot(to.x() - from.x(), to.y() - from.y());
			float segmentProgress = Math.min(1.0F, remaining / Math.max(0.001F, length));
			drawLogoTraceSegment(context, from, to, segmentProgress, color);
			remaining -= length;
		}
	}

	private void drawLogoTraceSegment(DrawContext context, LogoTracePoint from, LogoTracePoint to, float progress, int color) {
		float endX = GlibUi.lerp(from.x(), to.x(), progress);
		float endY = GlibUi.lerp(from.y(), to.y(), progress);
		int steps = Math.max(1, (int) Math.ceil(Math.hypot(endX - from.x(), endY - from.y())));
		for (int step = 0; step <= steps; step++) {
			float amount = step / (float) steps;
			int x = Math.round(GlibUi.lerp(from.x(), endX, amount));
			int y = Math.round(GlibUi.lerp(from.y(), endY, amount));
			context.fill(x - 1, y - 1, x + 1, y + 1, color);
		}
	}

	private float rootControlsAlpha() {
		if (!rootMenu) {
			return 1.0F;
		}
		return GlibUi.easeInOutCubic(clamp01((screenAnimator.secondsSinceOpen() - TYPE_START_SECONDS) / BUTTON_FADE_SECONDS));
	}

	private void updateRootControls(float alpha) {
		if (!rootMenu) {
			return;
		}
		boolean ready = alpha >= 0.98F && !screenAnimator.closing();
		for (GlibButton button : actionButtons) {
			button.setRenderAlpha(alpha);
			button.active = ready;
		}
		if (backButton != null) {
			backButton.setRenderAlpha(alpha);
			backButton.active = ready;
		}
		if (settingsButton != null) {
			settingsButton.setRenderAlpha(alpha);
			settingsButton.active = ready;
		}
		if (friendsButton != null) {
			friendsButton.setRenderAlpha(alpha);
			friendsButton.active = ready;
		}
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	private static float logoTraceLength() {
		float length = 0.0F;
		for (int index = 1; index < LOGO_TRACE.size(); index++) {
			LogoTracePoint from = LOGO_TRACE.get(index - 1);
			LogoTracePoint to = LOGO_TRACE.get(index);
			length += (float) Math.hypot(to.x() - from.x(), to.y() - from.y());
		}
		return length;
	}

	private void openSettings() {
		if (client == null || settingsScreenFactory == null) {
			return;
		}
		client.setScreen(settingsScreenFactory.create(this));
	}

	private void openFriends() {
		if (client != null) {
			client.setScreen(new GlibSocialScreen(this));
		}
	}

	private static Screen glibSettingsScreen(Screen parent) {
		return GlibConfigScreen.builder(parent, Text.literal("G'Lib Settings"))
			.action(Text.literal("No settings are available yet"), () -> {
			})
			.build();
	}

	private static List<GlibMenuAction> rootActions() {
		GlibMenuRegistry.loadContributors();
		List<GlibMenuAction> actions = new ArrayList<>();
		for (GlibMenuEntry entry : GlibMenuRegistry.entries()) {
			actions.add(new GlibMenuAction(entry.id(), entry.label(), entry.accentColor(), () -> MinecraftClient.getInstance().setScreen(entry.screenFactory().create(MinecraftClient.getInstance().currentScreen))));
		}
		return actions;
	}

	private static Identifier logoTexture(String name) {
		return Identifier.of("glib", "textures/gui/gnetwork_letter_" + name + ".png");
	}

	private record LogoPiece(Identifier texture, int width, int height, int targetX, int targetY, int flyX, int flyY) {
	}

	private record LogoTracePoint(float x, float y) {
	}
}
