package io.github.caspe.glib.client.social;

import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibNetworkSettingsScreen extends Screen {
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int LINE = 0x80394655;
	private static final int ACCENT = 0xFF45D483;
	private static final int MUTED = 0xFF9EA7B3;
	private final Screen parent;
	private final Snapshot snapshot;
	private TextFieldWidget endpointField;
	private String status = "The URL is public configuration; it is not a secret.";
	private boolean confirmDelete;
	private GlibButton deleteButton;

	public GlibNetworkSettingsScreen(Screen parent, Snapshot snapshot) {
		super(Text.literal("G'Network Settings"));
		this.parent = parent;
		this.snapshot = snapshot;
	}

	@Override
	protected void init() {
		GlibRect panel = panelRect();
		endpointField = new TextFieldWidget(textRenderer, panel.x() + 18, panel.y() + 70, panel.width() - 130, 22, Text.literal("Backend URL"));
		endpointField.setMaxLength(256);
		endpointField.setText(GlibNetworkConfig.get().apiUrl());
		addDrawableChild(endpointField);
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 104, panel.y() + 70, 86, 22, Text.literal("Save URL"), ACCENT, this::save));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 68, panel.y() + 17, 50, 22, Text.literal("Back"), 0xFFFFC857, this::close));
		deleteButton = addDrawableChild(new GlibButton(panel.x() + panel.width() - 184, panel.y() + 17, 110, 22,
			Text.literal("Delete My Data"), 0xFFFF718D, this::deleteAccount));

		if (snapshot != null) {
			int y = panel.y() + 140;
			int max = Math.max(0, (panel.height() - 170) / 28);
			for (int index = 0; index < Math.min(max, snapshot.blocked().size()); index++) {
				Player blocked = snapshot.blocked().get(index);
				addDrawableChild(new GlibButton(panel.x() + 22, y, panel.width() - 44, 22,
					Text.literal("Unblock " + blocked.username()), 0xFFFF718D, () -> unblock(blocked)));
				y += 28;
			}
		}
	}

	private void save() {
		try {
			GlibNetworkConfig.save(endpointField.getText());
			GlibSocialClient.INSTANCE.reset();
			status = "Saved. New connections will use this URL.";
		} catch (IllegalArgumentException | IOException exception) {
			status = exception.getMessage() == null ? "Could not save the URL." : exception.getMessage();
		}
	}

	private void unblock(Player player) {
		if (client == null) return;
		status = "Unblocking " + player.username() + "...";
		GlibSocialClient.INSTANCE.unblock(player.playerUuid()).whenComplete((ignored, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			status = player.username() + " was unblocked. Reopen this screen to refresh the list.";
		}));
	}

	private void deleteAccount() {
		if (!confirmDelete) {
			confirmDelete = true;
			deleteButton.setMessage(Text.literal("Confirm Delete"));
			status = "This permanently deletes your friends, requests, blocks, messages, and sessions.";
			return;
		}
		if (client == null) return;
		deleteButton.active = false;
		GlibSocialClient.INSTANCE.deleteAccount().whenComplete((ignored, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				deleteButton.active = true;
				return;
			}
			GlibSocialClient.INSTANCE.reset();
			status = "Your G'Network data was deleted.";
			confirmDelete = false;
			deleteButton.setMessage(Text.literal("Deleted"));
		}));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		GlibRect panel = panelRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 18, panel.y() + 19, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("Railway backend URL (HTTPS)"), panel.x() + 18, panel.y() + 53, MUTED);
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, panel.width() - 36)), panel.x() + 18, panel.y() + 101, MUTED);
		context.drawTextWithShadow(textRenderer, Text.literal("Blocked players"), panel.x() + 18, panel.y() + 121, 0xFFFFFFFF);
		GlibUi.drawPanel(context, panel.x() + 14, panel.y() + 135, panel.width() - 28, panel.height() - 151, PANEL_SOFT, LINE);
		if (snapshot == null || snapshot.blocked().isEmpty()) {
			context.drawTextWithShadow(textRenderer, Text.literal("No blocked players."), panel.x() + 22, panel.y() + 148, MUTED);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(620, width - 24);
		int panelHeight = Math.min(420, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}
}
