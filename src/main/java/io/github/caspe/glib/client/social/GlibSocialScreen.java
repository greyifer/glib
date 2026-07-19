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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibSocialScreen extends Screen {
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int LINE = 0x80394655;
	private static final int ACCENT = 0xFF45D483;
	private static final int MUTED = 0xFF9EA7B3;
	private static final int DANGER = 0xFFFF718D;
	private final Screen parent;
	private final Snapshot snapshot;
	private final String initialStatus;
	private final int friendPage;
	private final int requestPage;
	private final List<Row> rows = new ArrayList<>();
	private TextFieldWidget usernameField;
	private String status;
	private boolean loading;
	private AutoCloseable eventSubscription;

	public GlibSocialScreen(Screen parent) {
		this(parent, null, "Connecting securely...", 0, 0);
	}

	private GlibSocialScreen(Screen parent, Snapshot snapshot, String status, int friendPage, int requestPage) {
		super(Text.literal("Friends & Chat"));
		this.parent = parent;
		this.snapshot = snapshot;
		this.initialStatus = status;
		this.status = status;
		this.friendPage = Math.max(0, friendPage);
		this.requestPage = Math.max(0, requestPage);
	}

	@Override
	protected void init() {
		rows.clear();
		GlibRect panel = panelRect();
		usernameField = new TextFieldWidget(textRenderer, panel.x() + 20, panel.y() + 50, 174, 20, Text.literal("Minecraft username"));
		usernameField.setMaxLength(16);
		usernameField.setPlaceholder(Text.literal("Minecraft username"));
		addDrawableChild(usernameField);
		addDrawableChild(new GlibButton(panel.x() + 200, panel.y() + 49, 88, 22, Text.literal("Add Friend"), ACCENT, this::addFriend));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 154, panel.y() + 19, 58, 22, Text.literal("Refresh"), 0xFF60A5FA, this::refresh));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 90, panel.y() + 19, 28, 22, Text.literal("⚙"), 0xFF60A5FA, this::openSettings));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 56, panel.y() + 19, 36, 22, Text.literal("Back"), 0xFFFFC857, this::close));
		if (snapshot != null) buildRows(panel);
		if (eventSubscription == null) {
			eventSubscription = GlibSocialClient.INSTANCE.listen(event -> {
				if (!event.type().equals("ready") && !event.type().equals("chat.read")) refresh();
			});
		}
		if (snapshot == null && !loading) refresh();
	}

	private void buildRows(GlibRect panel) {
		int contentTop = panel.y() + 98;
		int columnGap = 14;
		int columnWidth = (panel.width() - 54) / 2;
		int leftX = panel.x() + 20;
		int rightX = leftX + columnWidth + columnGap;
		int maxRows = Math.max(1, (panel.height() - 180) / 28);

		int friendStart = Math.min(snapshot.friends().size(), friendPage * maxRows);
		int friendEnd = Math.min(snapshot.friends().size(), friendStart + maxRows);
		for (int index = friendStart; index < friendEnd; index++) {
			Player friend = snapshot.friends().get(index);
			int y = contentTop + (index - friendStart) * 28;
			String label = (friend.online() ? "● " : "○ ") + friend.username();
			GlibButton chat = addDrawableChild(new GlibButton(leftX, y, columnWidth - 62, 22, Text.literal(label), friend.online() ? ACCENT : MUTED,
				() -> openChat(friend)));
			GlibButton remove = addDrawableChild(new GlibButton(leftX + columnWidth - 56, y, 56, 22, Text.literal("Remove"), DANGER,
				() -> runAction(GlibSocialClient.INSTANCE.removeFriend(friend.playerUuid()), "Friend removed.")));
			rows.add(new Row(chat, remove));
		}

		List<RequestItem> requests = new ArrayList<>();
		for (FriendRequest request : snapshot.incoming()) requests.add(new RequestItem(request, true));
		for (FriendRequest request : snapshot.outgoing()) requests.add(new RequestItem(request, false));
		int requestStart = Math.min(requests.size(), requestPage * maxRows);
		int requestEnd = Math.min(requests.size(), requestStart + maxRows);
		for (int index = requestStart; index < requestEnd; index++) {
			RequestItem item = requests.get(index);
			FriendRequest request = item.request();
			int y = contentTop + (index - requestStart) * 28;
			if (item.incoming()) {
				GlibButton accept = addDrawableChild(new GlibButton(rightX, y, columnWidth - 70, 22,
					Text.literal("Accept " + request.player().username()), ACCENT,
					() -> runAction(GlibSocialClient.INSTANCE.acceptRequest(request.requestId()), "Friend request accepted.")));
				GlibButton decline = addDrawableChild(new GlibButton(rightX + columnWidth - 64, y, 64, 22, Text.literal("Decline"), DANGER,
					() -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Friend request declined.")));
				rows.add(new Row(accept, decline));
			} else {
				GlibButton waiting = addDrawableChild(new GlibButton(rightX, y, columnWidth - 70, 22,
					Text.literal("Pending: " + request.player().username()), MUTED, () -> {}));
				waiting.active = false;
				GlibButton cancel = addDrawableChild(new GlibButton(rightX + columnWidth - 64, y, 64, 22, Text.literal("Cancel"), DANGER,
					() -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Friend request cancelled.")));
				rows.add(new Row(waiting, cancel));
			}
		}
		buildPageControls(panel, leftX, rightX, columnWidth, maxRows, requests.size());
	}

	private void buildPageControls(GlibRect panel, int leftX, int rightX, int columnWidth, int maxRows, int requestCount) {
		int y = panel.y() + panel.height() - 29;
		if (friendPage > 0) {
			addDrawableChild(new GlibButton(leftX, y, 54, 20, Text.literal("Prev"), 0xFF60A5FA,
				() -> changePage(friendPage - 1, requestPage)));
		}
		if ((friendPage + 1) * maxRows < snapshot.friends().size()) {
			addDrawableChild(new GlibButton(leftX + columnWidth - 54, y, 54, 20, Text.literal("Next"), 0xFF60A5FA,
				() -> changePage(friendPage + 1, requestPage)));
		}
		if (requestPage > 0) {
			addDrawableChild(new GlibButton(rightX, y, 54, 20, Text.literal("Prev"), 0xFF60A5FA,
				() -> changePage(friendPage, requestPage - 1)));
		}
		if ((requestPage + 1) * maxRows < requestCount) {
			addDrawableChild(new GlibButton(rightX + columnWidth - 54, y, 54, 20, Text.literal("Next"), 0xFF60A5FA,
				() -> changePage(friendPage, requestPage + 1)));
		}
	}

	private void changePage(int newFriendPage, int newRequestPage) {
		if (client != null) client.setScreen(new GlibSocialScreen(parent, snapshot, status, newFriendPage, newRequestPage));
	}

	private void addFriend() {
		String username = usernameField.getText().trim();
		if (username.isEmpty()) {
			status = "Enter a Minecraft username.";
			return;
		}
		runAction(GlibSocialClient.INSTANCE.sendFriendRequest(username), "Friend request sent to " + username + ".");
	}

	private void refresh() {
		if (loading || client == null) return;
		loading = true;
		status = "Refreshing...";
		GlibSocialClient.INSTANCE.snapshot().whenComplete((loaded, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			client.setScreen(new GlibSocialScreen(parent, loaded, "Connected as " + loaded.me().username() + ".", friendPage, requestPage));
		}));
	}

	private void runAction(CompletableFuture<Void> action, String success) {
		if (loading || client == null) return;
		loading = true;
		status = "Working...";
		action.whenComplete((ignored, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			status = success;
			refresh();
		}));
	}

	private void openChat(Player friend) {
		if (client != null) client.setScreen(new GlibChatScreen(this, friend));
	}

	private void openSettings() {
		if (client != null) client.setScreen(new GlibNetworkSettingsScreen(this, snapshot));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		GlibRect panel = panelRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 20, panel.y() + 20, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(status == null ? initialStatus : status), panel.x() + 302, panel.y() + 56,
			status != null && (status.contains("failed") || status.contains("invalid") || status.contains("Could not")) ? DANGER : MUTED);
		int contentTop = panel.y() + 82;
		int columnGap = 14;
		int columnWidth = (panel.width() - 54) / 2;
		int leftX = panel.x() + 20;
		int rightX = leftX + columnWidth + columnGap;
		GlibUi.drawPanel(context, leftX - 6, contentTop, columnWidth + 12, panel.height() - 116, PANEL_SOFT, LINE);
		GlibUi.drawPanel(context, rightX - 6, contentTop, columnWidth + 12, panel.height() - 116, PANEL_SOFT, LINE);
		context.drawTextWithShadow(textRenderer, Text.literal("Friends" + count(snapshot == null ? 0 : snapshot.friends().size())), leftX, contentTop + 6, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("Requests" + count(snapshot == null ? 0 : snapshot.incoming().size() + snapshot.outgoing().size())), rightX, contentTop + 6, 0xFFFFFFFF);
		if (snapshot != null && snapshot.friends().isEmpty()) {
			context.drawTextWithShadow(textRenderer, Text.literal("No friends yet."), leftX, contentTop + 35, MUTED);
		}
		if (snapshot != null && snapshot.incoming().isEmpty() && snapshot.outgoing().isEmpty()) {
			context.drawTextWithShadow(textRenderer, Text.literal("No pending requests."), rightX, contentTop + 35, MUTED);
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER && usernameField != null && usernameField.isFocused()) {
			addFriend();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void removed() {
		if (eventSubscription != null) {
			try {
				eventSubscription.close();
			} catch (Exception ignored) {
			}
			eventSubscription = null;
		}
		super.removed();
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
		int panelWidth = Math.min(700, width - 24);
		int panelHeight = Math.min(440, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private static String count(int value) {
		return value == 0 ? "" : " (" + value + ")";
	}

	private record Row(GlibButton primary, GlibButton secondary) {
	}

	private record RequestItem(FriendRequest request, boolean incoming) {
	}
}
