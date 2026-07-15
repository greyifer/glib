package io.github.caspe.glib.api.client.editor;

import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Base workbench for rich editors that need custom previews, lists, canvases, or forms.
 * G'Lib owns the screen lifecycle and chrome; the consuming mod only renders inside the workspace.
 */
@Environment(EnvType.CLIENT)
public abstract class GlibWorkbenchScreen extends Screen {
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int LINE = 0x80394655;
	private static final int MUTED = 0xFF9EA7B3;

	private final Screen parent;
	private final Text subtitle;
	private final int accentColor;

	protected GlibWorkbenchScreen(Screen parent, Text title, Text subtitle, int accentColor) {
		super(title);
		this.parent = parent;
		this.subtitle = subtitle == null ? Text.empty() : subtitle;
		this.accentColor = accentColor;
	}

	@Override
	protected final void init() {
		initWorkbench(workspaceRect());
		GlibRect panel = panelRect();
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 168,
			panel.y() + panel.height() - 37, 150, 22, Text.literal("Back"), accentColor, this::close));
	}

	protected abstract void initWorkbench(GlibRect workspace);

	@Override
	public final void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderChrome(context);
		GlibRect workspace = workspaceRect();
		context.enableScissor(workspace.x(), workspace.y(), workspace.x() + workspace.width(), workspace.y() + workspace.height());
		renderWorkbench(context, workspace, mouseX, mouseY, delta);
		context.disableScissor();
		super.render(context, mouseX, mouseY, delta);
	}

	protected abstract void renderWorkbench(DrawContext context, GlibRect workspace, int mouseX, int mouseY, float delta);

	protected final GlibRect workspaceRect() {
		GlibRect panel = panelRect();
		return new GlibRect(panel.x() + 16, panel.y() + 62, panel.width() - 32, panel.height() - 108);
	}

	protected final int accentColor() {
		return accentColor;
	}

	protected final Screen parentScreen() {
		return parent;
	}

	/** Allows workbenches with modes or selections to update their explanatory line. */
	protected Text workbenchSubtitle() {
		return subtitle;
	}

	protected final void drawWorkbenchPanel(DrawContext context, int x, int y, int width, int height) {
		GlibUi.drawPanel(context, x, y, width, height, PANEL_SOFT, LINE);
	}

	@Override
	public final void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void renderChrome(DrawContext context) {
		GlibRect panel = panelRect();
		GlibRect workspace = workspaceRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, accentColor);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 22, panel.y() + 20, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, workbenchSubtitle(), panel.x() + 22, panel.y() + 37, MUTED);
		GlibUi.drawPanel(context, workspace.x(), workspace.y(), workspace.width(), workspace.height(), PANEL_SOFT, LINE);
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(1120, width - 24);
		int panelHeight = Math.min(720, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}
}
