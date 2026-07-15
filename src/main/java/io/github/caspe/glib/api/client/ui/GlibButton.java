package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class GlibButton extends PressableWidget {
	private final Runnable pressAction;
	private final int accentColor;
	private float hoverAmount;
	private float renderAlpha = 1.0F;

	public GlibButton(int x, int y, int width, int height, Text message, int accentColor, Runnable pressAction) {
		super(x, y, width, height, message);
		this.accentColor = accentColor;
		this.pressAction = pressAction;
	}

	@Override
	public void onPress() {
		pressAction.run();
	}

	public void setRenderAlpha(float renderAlpha) {
		this.renderAlpha = Math.max(0.0F, Math.min(1.0F, renderAlpha));
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		hoverAmount = GlibUi.smoothHover(hoverAmount, active && isHovered());
		boolean visuallyActive = active || renderAlpha < 0.999F;
		int fill = visuallyActive ? GlibUi.mixColor(0xE01D2430, 0xF02B3340, hoverAmount) : 0x8030353D;
		int border = visuallyActive ? GlibUi.mixColor(0x804B5666, accentColor, hoverAmount) : 0x604B5666;
		context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), GlibUi.withAlpha(fill, renderAlpha));
		context.fill(getX(), getY(), getX() + getWidth(), getY() + 1, GlibUi.withAlpha(border, renderAlpha));
		context.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), GlibUi.withAlpha(0x8010161F, renderAlpha));
		context.fill(getX(), getY(), getX() + 1, getY() + getHeight(), GlibUi.withAlpha(border, renderAlpha));
		context.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), GlibUi.withAlpha(border, renderAlpha));

		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		String label = renderer.trimToWidth(getMessage().getString(), getWidth() - 12);
		int color = visuallyActive ? 0xFFFFFFFF : 0xFF9EA7B3;
		int textX = getX() + (getWidth() - renderer.getWidth(label)) / 2;
		int textY = getY() + (getHeight() - 8) / 2;
		int textAlpha = Math.round(renderAlpha * 255.0F);
		if (textAlpha >= 4) {
			context.drawTextWithShadow(renderer, Text.literal(label), textX, textY, GlibUi.withAlpha(color, textAlpha));
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}
