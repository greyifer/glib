package io.github.caspe.glib.client.social;

import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibLoadingScreen;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibNetworkSettingsScreen extends Screen {
	private static final int BACKDROP = 0x7805090E;
	private static final int PANEL = 0xF410151D;
	private static final int SURFACE = 0xE61A212B;
	private static final int LINE = 0x70424C5B;
	private static final int ACCENT = 0xFF4CDB8A;
	private static final int MUTED = 0xFF98A3B1;
	private static final int DANGER = 0xFFFF718D;

	private final Screen parent;
	private final List<Player> blocked;
	private final GlibLoadingScreen loadingScreen = new GlibLoadingScreen(ACCENT, 0xFF101722, 0.14F, 30.0F);
	private String status = "Manage who can contact you through G'Network.";
	private boolean confirmDelete;
	private boolean changed;
	private GlibButton deleteButton;

	public GlibNetworkSettingsScreen(Screen parent, Snapshot snapshot) {
		super(Text.literal("Social Settings"));
		this.parent = parent;
		this.blocked = new ArrayList<>(snapshot == null ? List.of() : snapshot.blocked());
	}

	@Override
	protected void init() {
		GlibRect panel = panelRect();
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 40, panel.y() + 16, 24, 22,
			Text.literal("X"), 0xFFFFC857, this::close));

		GlibRect blockedArea = blockedArea(panel);
		int maxRows = Math.max(1, blockedArea.height() / 34);
		for (int index = 0; index < Math.min(maxRows, blocked.size()); index++) {
			Player player = blocked.get(index);
			addDrawableChild(new GlibButton(blockedArea.x() + 8, blockedArea.y() + 8 + index * 34,
				blockedArea.width() - 16, 26, Text.literal("Unblock " + player.username()), MUTED, () -> unblock(player)));
		}

		deleteButton = addDrawableChild(new GlibButton(panel.x() + panel.width() - 142, panel.y() + panel.height() - 71,
			124, 28, Text.literal(confirmDelete ? "Confirm delete" : "Delete my data"), DANGER, this::deleteAccount));
	}

	@Override
	public void tick() {
		super.tick();
		if (loadingScreen.finished()) loadingScreen.cancel();
	}

	private void unblock(Player player) {
		if (client == null || loadingScreen.active()) return;
		status = "Unblocking " + player.username() + "...";
		loadingScreen.start("Updating privacy settings...", 30.0F);
		GlibSocialClient.INSTANCE.unblock(player.playerUuid()).whenComplete((ignored, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			loadingScreen.finish();
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			blocked.removeIf(entry -> entry.playerUuid().equals(player.playerUuid()));
			changed = true;
			status = player.username() + " can contact you again.";
			clearChildren();
			init();
		}));
	}

	private void deleteAccount() {
		if (!confirmDelete) {
			confirmDelete = true;
			deleteButton.setMessage(Text.literal("Confirm delete"));
			status = "This permanently removes your friends, chats, requests, and blocks.";
			return;
		}
		if (client == null || loadingScreen.active()) return;
		deleteButton.active = false;
		loadingScreen.start("Deleting your social data...", 30.0F);
		GlibSocialClient.INSTANCE.deleteAccount().whenComplete((ignored, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			loadingScreen.finish();
			if (error != null) {
				status = SocialScreenSupport.message(error);
				deleteButton.active = true;
				return;
			}
			GlibSocialClient.INSTANCE.reset();
			blocked.clear();
			changed = true;
			status = "Your G'Network social data was deleted.";
			confirmDelete = false;
			deleteButton.setMessage(Text.literal("Deleted"));
		}));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		GlibRect panel = panelRect();
		GlibRect blockedArea = blockedArea(panel);
		context.fill(0, 0, width, height, BACKDROP);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);

		context.drawTextWithShadow(textRenderer, Text.literal("Social settings"), panel.x() + 18, panel.y() + 17, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("Privacy, blocked players, and account data"), panel.x() + 18, panel.y() + 32, MUTED);
		context.drawTextWithShadow(textRenderer, Text.literal("Blocked players"), panel.x() + 18, panel.y() + 63, 0xFFFFFFFF);
		GlibUi.drawPanel(context, blockedArea.x(), blockedArea.y(), blockedArea.width(), blockedArea.height(), SURFACE, LINE);
		if (blocked.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("Nobody is blocked"), blockedArea.x() + blockedArea.width() / 2,
				blockedArea.y() + blockedArea.height() / 2 - 5, MUTED);
		}

		int dangerY = panel.y() + panel.height() - 86;
		context.fill(panel.x() + 18, dangerY, panel.x() + panel.width() - 18, dangerY + 1, LINE);
		context.drawTextWithShadow(textRenderer, Text.literal("Delete social data"), panel.x() + 18, dangerY + 16, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("Permanently removes your G'Network account and conversations."), panel.x() + 18, dangerY + 32, MUTED);
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, panel.width() - 36)), panel.x() + 18,
			panel.y() + panel.height() - 22, status.contains("failed") || status.contains("Could not") ? DANGER : MUTED);
		super.render(context, mouseX, mouseY, delta);
		if (loadingScreen.active()) loadingScreen.render(context, textRenderer, blockedArea);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void close() {
		if (client == null) return;
		client.setScreen(parent);
		if (changed && parent instanceof GlibSocialScreen social) social.refresh();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private GlibRect blockedArea(GlibRect panel) {
		return new GlibRect(panel.x() + 18, panel.y() + 80, panel.width() - 36, panel.height() - 184);
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(580, width - 24);
		int panelHeight = Math.min(410, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}
}
