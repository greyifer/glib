package io.github.caspe.glib.api.client.editor;

import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.Objects;

/** G'Lib shell for rich mod-specific content such as galleries, player lists, and reports. */
@Environment(EnvType.CLIENT)
public final class GlibContentScreen extends Screen {
	@FunctionalInterface
	public interface ContentFactory {
		ClickableWidget create(Screen host, GlibRect content);
	}

	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int LINE = 0x80394655;

	private final Screen parent;
	private final Text subtitle;
	private final int accentColor;
	private final ContentFactory contentFactory;
	private final Runnable closeAction;
	private ClickableWidget content;

	private GlibContentScreen(Builder builder) {
		super(builder.title);
		this.parent = builder.parent;
		this.subtitle = builder.subtitle;
		this.accentColor = builder.accentColor;
		this.contentFactory = builder.contentFactory;
		this.closeAction = builder.closeAction;
	}

	public static Builder builder(Screen parent, Text title) {
		return new Builder(parent, title);
	}

	@Override
	protected void init() {
		GlibRect contentRect = contentRect();
		content = contentFactory.create(this, contentRect);
		if (content != null) addDrawableChild(content);
		GlibRect panel = panelRect();
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 168,
			panel.y() + panel.height() - 37, 150, 22, Text.literal("Back"), accentColor, this::close));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		GlibRect panel = panelRect();
		GlibRect contentRect = contentRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, accentColor);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 22, panel.y() + 20, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, subtitle, panel.x() + 22, panel.y() + 37, 0xFF9EA7B3);
		GlibUi.drawPanel(context, contentRect.x(), contentRect.y(), contentRect.width(), contentRect.height(), PANEL_SOFT, LINE);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// The G'Lib shell supplies its own background and must remain pixel-sharp.
	}

	@Override
	public void close() {
		closeAction.run();
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

	private GlibRect contentRect() {
		GlibRect panel = panelRect();
		return new GlibRect(panel.x() + 16, panel.y() + 62, panel.width() - 32, panel.height() - 108);
	}

	public static final class Builder {
		private final Screen parent;
		private final Text title;
		private Text subtitle = Text.empty();
		private int accentColor = 0xFF45D483;
		private ContentFactory contentFactory = (host, rect) -> null;
		private Runnable closeAction = () -> {};

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

		public Builder content(ContentFactory contentFactory) {
			this.contentFactory = Objects.requireNonNull(contentFactory, "contentFactory");
			return this;
		}

		public Builder onClose(Runnable closeAction) {
			this.closeAction = closeAction == null ? () -> {} : closeAction;
			return this;
		}

		public GlibContentScreen build() {
			return new GlibContentScreen(this);
		}
	}
}
