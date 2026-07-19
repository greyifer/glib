package io.github.caspe.glib.client.social;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import io.github.caspe.glib.api.skin.FaceOptions;
import io.github.caspe.glib.api.skin.SkinRenderers;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibSocialScreen extends Screen {
	private static final int BACKDROP = 0x76060A10;
	private static final int PANEL = 0xF211161E;
	private static final int SIDEBAR = 0xF4171D27;
	private static final int SURFACE = 0xE51A212C;
	private static final int SURFACE_HOVER = 0xF0252E3B;
	private static final int LINE = 0x70404B5C;
	private static final int ACCENT = 0xFF4CDB8A;
	private static final int BLUE = 0xFF67A8FF;
	private static final int MUTED = 0xFF939EAC;
	private static final int FAINT = 0xFF66717F;
	private static final int DANGER = 0xFFFF718D;
	private static final int ROW_HEIGHT = 42;
	private static final int ROW_GAP = 6;
	private static final FaceOptions FACE_OPTIONS = FaceOptions.ofSize(24);

	private final Screen parent;
	private final List<ClickableWidget> dynamicWidgets = new ArrayList<>();
	private Snapshot snapshot;
	private Section section;
	private int page;
	private TextFieldWidget searchField;
	private String status;
	private String searchQuery = "";
	private List<PlayerSearchResult> searchResults = List.of();
	private long searchDueAt;
	private int searchRevision;
	private int renderedRows;
	private boolean loading;
	private boolean searching;
	private GlibButton requestTabButton;
	private AutoCloseable eventSubscription;

	public GlibSocialScreen(Screen parent) {
		this(parent, null, "Connecting securely...", Section.FRIENDS, 0);
	}

	private GlibSocialScreen(Screen parent, Snapshot snapshot, String status, Section section, int page) {
		super(Text.literal("G'Network"));
		this.parent = parent;
		this.snapshot = snapshot;
		this.status = status;
		this.section = section;
		this.page = Math.max(0, page);
	}

	@Override
	protected void init() {
		dynamicWidgets.clear();
		GlibRect panel = panelRect();
		int sidebarX = panel.x() + 14;
		int contentX = contentX(panel);
		int contentWidth = contentWidth(panel);

		addDrawableChild(new GlibButton(sidebarX, panel.y() + 78, 132, 24, Text.literal("Friends"), ACCENT,
			() -> selectSection(Section.FRIENDS)));
		requestTabButton = addDrawableChild(new GlibButton(sidebarX, panel.y() + 108, 132, 24, Text.literal(requestLabel()), BLUE,
			() -> selectSection(Section.REQUESTS)));
		addDrawableChild(new GlibButton(sidebarX, panel.y() + panel.height() - 66, 64, 24, Text.literal("Refresh"), BLUE, this::refresh));
		addDrawableChild(new GlibButton(sidebarX + 68, panel.y() + panel.height() - 66, 64, 24, Text.literal("Settings"), MUTED, this::openSettings));

		int backWidth = 28;
		int searchWidth = Math.max(130, contentWidth - 246);
		int searchX = contentX + contentWidth - searchWidth - backWidth - 8;
		searchField = new TextFieldWidget(textRenderer, searchX, panel.y() + 16, searchWidth, 22, Text.literal("Search G'Lib players"));
		searchField.setMaxLength(16);
		searchField.setText(searchQuery);
		searchField.setChangedListener(this::searchChanged);
		addDrawableChild(searchField);
		addDrawableChild(new GlibButton(contentX + contentWidth - backWidth, panel.y() + 16, backWidth, 22,
			Text.literal("X"), 0xFFFFC857, this::close));

		if (eventSubscription == null) {
			eventSubscription = GlibSocialClient.INSTANCE.listen(event -> {
				if (event.type().startsWith("friend.") || event.type().startsWith("presence.")) refresh();
			});
		}
		if (validSearch(searchQuery)) {
			searching = false;
			searchRevision++;
			searchDueAt = Util.getMeasuringTimeMs() + 150L;
		}
		rebuildRows();
		if (snapshot == null && !loading) refresh();
	}

	@Override
	public void tick() {
		super.tick();
		if (searchDueAt > 0L && Util.getMeasuringTimeMs() >= searchDueAt) {
			searchDueAt = 0L;
			startSearch(searchQuery, searchRevision);
		}
	}

	private void searchChanged(String value) {
		searchQuery = value.trim();
		searchRevision++;
		searchDueAt = 0L;
		searching = false;
		searchResults = List.of();
		page = 0;
		if (validSearch(searchQuery)) searchDueAt = Util.getMeasuringTimeMs() + 275L;
		rebuildRows();
	}

	private void startSearch(String query, int revision) {
		if (!validSearch(query) || client == null) return;
		searching = true;
		rebuildRows();
		GlibSocialClient.INSTANCE.searchPlayers(query).whenComplete((results, error) -> client.execute(() -> {
			if (client.currentScreen != this || revision != searchRevision || !searchQuery.equals(query)) return;
			searching = false;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				searchResults = List.of();
			} else {
				searchResults = results;
			}
			rebuildRows();
		}));
	}

	private void rebuildRows() {
		for (ClickableWidget widget : dynamicWidgets) remove(widget);
		dynamicWidgets.clear();
		renderedRows = 0;
		if (client == null) return;

		if (validSearch(searchQuery)) {
			buildSearchRows();
			return;
		}
		if (snapshot == null) return;
		if (section == Section.FRIENDS) buildFriendRows();
		else buildRequestRows();
	}

	private void buildFriendRows() {
		int maxRows = maxRows();
		int start = Math.min(snapshot.friends().size(), page * maxRows);
		int end = Math.min(snapshot.friends().size(), start + maxRows);
		for (int index = start; index < end; index++) {
			Player friend = snapshot.friends().get(index);
			addRow(index - start, friend.playerUuid(), friend.username(), friend.online() ? "Online" : "Offline",
				friend.online() ? ACCENT : MUTED, "Message", () -> openChat(friend),
				"Remove", DANGER, () -> runAction(GlibSocialClient.INSTANCE.removeFriend(friend.playerUuid()), friend.username() + " was removed."));
		}
		renderedRows = end - start;
		addPageControls(snapshot.friends().size(), maxRows);
	}

	private void buildRequestRows() {
		List<RequestItem> requests = new ArrayList<>();
		for (FriendRequest request : snapshot.incoming()) requests.add(new RequestItem(request, true));
		for (FriendRequest request : snapshot.outgoing()) requests.add(new RequestItem(request, false));
		int maxRows = maxRows();
		int start = Math.min(requests.size(), page * maxRows);
		int end = Math.min(requests.size(), start + maxRows);
		for (int index = start; index < end; index++) {
			RequestItem item = requests.get(index);
			FriendRequest request = item.request();
			if (item.incoming()) {
				addRow(index - start, request.player().playerUuid(), request.player().username(), "Wants to be friends", ACCENT,
					"Accept", () -> runAction(GlibSocialClient.INSTANCE.acceptRequest(request.requestId()), "You are now friends."),
					"Decline", DANGER, () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Request declined."));
			} else {
				addRow(index - start, request.player().playerUuid(), request.player().username(), "Request sent", MUTED,
					"Pending", null, "Cancel", DANGER,
					() -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Request cancelled."));
			}
		}
		renderedRows = end - start;
		addPageControls(requests.size(), maxRows);
	}

	private void buildSearchRows() {
		for (int index = 0; index < Math.min(maxRows(), searchResults.size()); index++) {
			PlayerSearchResult result = searchResults.get(index);
			switch (result.relationship()) {
				case "FRIEND" -> addRow(index, result.playerUuid(), result.username(), "Already friends", ACCENT,
					"Message", () -> openChat(playerFor(result)), null, MUTED, null);
				case "INCOMING" -> addRow(index, result.playerUuid(), result.username(), "Sent you a request", ACCENT,
					"Accept", result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.acceptRequest(result.requestId()), "You are now friends."),
					"Decline", DANGER, result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(result.requestId()), "Request declined."));
				case "OUTGOING" -> addRow(index, result.playerUuid(), result.username(), "Request sent", MUTED,
					"Pending", null, "Cancel", DANGER,
					result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(result.requestId()), "Request cancelled."));
				default -> addRow(index, result.playerUuid(), result.username(), "G'Lib player", BLUE,
					"Add friend", () -> runAction(GlibSocialClient.INSTANCE.sendFriendRequest(result.username()), "Request sent to " + result.username() + "."),
					null, MUTED, null);
			}
		}
		renderedRows = Math.min(maxRows(), searchResults.size());
	}

	private void addRow(int slot, UUID playerUuid, String username, String subtitle, int accent, String actionLabel,
		Runnable action, String secondaryLabel, int secondaryAccent, Runnable secondaryAction) {
		GlibRect panel = panelRect();
		int x = contentX(panel);
		int y = rowsTop(panel) + slot * (ROW_HEIGHT + ROW_GAP);
		int width = contentWidth(panel);
		int secondaryWidth = secondaryLabel == null ? 0 : 64;
		int primaryWidth = width - (secondaryWidth == 0 ? 0 : secondaryWidth + 6);
		SocialRowButton row = new SocialRowButton(x, y, primaryWidth, ROW_HEIGHT, playerUuid, username, subtitle, accent, actionLabel, action);
		dynamicWidgets.add(addDrawableChild(row));
		if (secondaryLabel != null) {
			GlibButton secondary = new GlibButton(x + primaryWidth + 6, y, secondaryWidth, ROW_HEIGHT,
				Text.literal(secondaryLabel), secondaryAccent, secondaryAction == null ? () -> {} : secondaryAction);
			secondary.active = secondaryAction != null;
			dynamicWidgets.add(addDrawableChild(secondary));
		}
	}

	private void addPageControls(int total, int maxRows) {
		if (total <= maxRows) return;
		GlibRect panel = panelRect();
		int y = panel.y() + panel.height() - 35;
		if (page > 0) {
			GlibButton previous = new GlibButton(contentX(panel), y, 56, 20, Text.literal("Previous"), BLUE, () -> changePage(page - 1));
			dynamicWidgets.add(addDrawableChild(previous));
		}
		if ((page + 1) * maxRows < total) {
			GlibButton next = new GlibButton(contentX(panel) + contentWidth(panel) - 44, y, 44, 20, Text.literal("Next"), BLUE, () -> changePage(page + 1));
			dynamicWidgets.add(addDrawableChild(next));
		}
	}

	private void changePage(int nextPage) {
		page = Math.max(0, nextPage);
		rebuildRows();
	}

	private void selectSection(Section next) {
		section = next;
		page = 0;
		if (searchField != null && !searchField.getText().isEmpty()) searchField.setText("");
		else rebuildRows();
	}

	private void refresh() {
		if (loading || client == null) return;
		loading = true;
		status = snapshot == null ? "Connecting securely..." : "Refreshing...";
		GlibSocialClient.INSTANCE.snapshot().whenComplete((loaded, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			snapshot = loaded;
			if (requestTabButton != null) requestTabButton.setMessage(Text.literal(requestLabel()));
			status = "Connected as " + loaded.me().username();
			rebuildRows();
			if (validSearch(searchQuery)) {
				searchRevision++;
				searchDueAt = Util.getMeasuringTimeMs();
			}
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

	private Player playerFor(PlayerSearchResult result) {
		if (snapshot != null) {
			for (Player friend : snapshot.friends()) {
				if (friend.playerUuid().equals(result.playerUuid())) return friend;
			}
		}
		return new Player(result.playerUuid(), result.username(), false, null, null);
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
		int sidebarWidth = sidebarWidth(panel);
		int contentX = contentX(panel);
		int contentWidth = contentWidth(panel);
		context.fill(0, 0, width, height, BACKDROP);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x() + 1, panel.y() + 1, panel.x() + sidebarWidth, panel.y() + panel.height() - 1, SIDEBAR);
		context.fill(panel.x() + sidebarWidth, panel.y() + 1, panel.x() + sidebarWidth + 1, panel.y() + panel.height() - 1, LINE);

		context.drawTextWithShadow(textRenderer, Text.literal("G'NETWORK"), panel.x() + 14, panel.y() + 18, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(snapshot == null ? "Signing in..." : snapshot.me().username()), panel.x() + 14, panel.y() + 37, MUTED);
		context.fill(panel.x() + 8, panel.y() + (section == Section.FRIENDS ? 78 : 108), panel.x() + 10,
			panel.y() + (section == Section.FRIENDS ? 102 : 132), section == Section.FRIENDS ? ACCENT : BLUE);

		String heading = validSearch(searchQuery) ? "Player search" : section == Section.FRIENDS ? "Friends" : "Requests";
		context.drawTextWithShadow(textRenderer, Text.literal(heading), contentX, panel.y() + 21, 0xFFFFFFFF);
		int searchLabelX = contentX + 104;
		context.drawTextWithShadow(textRenderer, Text.literal("Find players"), searchLabelX, panel.y() + 22, MUTED);
		context.fill(contentX, panel.y() + 48, contentX + contentWidth, panel.y() + 49, LINE);

		drawEmptyState(context, panel, contentX);
		String shownStatus = status == null ? "" : textRenderer.trimToWidth(status, 132);
		context.drawTextWithShadow(textRenderer, Text.literal(shownStatus), panel.x() + 14, panel.y() + panel.height() - 28,
			isError(status) ? DANGER : FAINT);
		super.render(context, mouseX, mouseY, delta);
	}

	private void drawEmptyState(DrawContext context, GlibRect panel, int contentX) {
		if (renderedRows > 0) return;
		int y = rowsTop(panel) + 18;
		String primary;
		String secondary;
		if (searchQuery.length() == 1) {
			primary = "Keep typing";
			secondary = "Enter at least two characters to search.";
		} else if (!searchQuery.isEmpty() && !validSearch(searchQuery)) {
			primary = "That search cannot be used";
			secondary = "Use 2-16 letters, numbers, or underscores.";
		} else if (validSearch(searchQuery) && searching) {
			primary = "Searching...";
			secondary = "Looking through registered G'Lib players.";
		} else if (validSearch(searchQuery)) {
			primary = "No matching players";
			secondary = "Only players who have signed into G'Network appear here.";
		} else if (snapshot == null) {
			primary = "Connecting to G'Network";
			secondary = "Minecraft is securely verifying your account.";
		} else if (section == Section.FRIENDS) {
			primary = "Your friends will appear here";
			secondary = "Use the search field above to find someone.";
		} else {
			primary = "No pending requests";
			secondary = "New incoming and outgoing requests appear here.";
		}
		context.drawTextWithShadow(textRenderer, Text.literal(primary), contentX, y, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(secondary), contentX, y + 17, MUTED);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
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

	private String requestLabel() {
		int count = snapshot == null ? 0 : snapshot.incoming().size();
		return count == 0 ? "Requests" : "Requests  " + count;
	}

	private int maxRows() {
		GlibRect panel = panelRect();
		return Math.max(1, (panel.height() - 106) / (ROW_HEIGHT + ROW_GAP));
	}

	private int rowsTop(GlibRect panel) {
		return panel.y() + 60;
	}

	private int sidebarWidth(GlibRect panel) {
		return Math.min(160, Math.max(146, panel.width() / 4));
	}

	private int contentX(GlibRect panel) {
		return panel.x() + sidebarWidth(panel) + 18;
	}

	private int contentWidth(GlibRect panel) {
		return panel.width() - sidebarWidth(panel) - 36;
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(760, width - 24);
		int panelHeight = Math.min(460, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private static boolean validSearch(String query) {
		return query.matches("[A-Za-z0-9_]{2,16}");
	}

	private static boolean isError(String value) {
		if (value == null) return false;
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.contains("failed") || lower.contains("invalid") || lower.contains("could not") || lower.contains("error");
	}

	private enum Section {
		FRIENDS,
		REQUESTS
	}

	private record RequestItem(FriendRequest request, boolean incoming) {
	}

	private static final class SocialRowButton extends PressableWidget {
		private final UUID playerUuid;
		private final String username;
		private final String subtitle;
		private final int accent;
		private final String actionLabel;
		private final Runnable action;
		private float hoverAmount;

		private SocialRowButton(int x, int y, int width, int height, UUID playerUuid, String username, String subtitle,
			int accent, String actionLabel, Runnable action) {
			super(x, y, width, height, Text.literal(username + ", " + subtitle + ", " + actionLabel));
			this.playerUuid = playerUuid;
			this.username = username;
			this.subtitle = subtitle;
			this.accent = accent;
			this.actionLabel = actionLabel;
			this.action = action;
			this.active = action != null;
		}

		@Override
		public void onPress() {
			if (action != null) action.run();
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			hoverAmount = GlibUi.smoothHover(hoverAmount, active && isHovered());
			int fill = GlibUi.mixColor(SURFACE, SURFACE_HOVER, hoverAmount);
			context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
			context.fill(getX(), getY(), getX() + 2, getY() + getHeight(), active ? accent : FAINT);
			context.fill(getX(), getY(), getX() + getWidth(), getY() + 1, LINE);

			int faceX = getX() + 9;
			int faceY = getY() + 9;
			if (!SkinRenderers.drawPlayerFace(context, new GameProfile(playerUuid, username), FACE_OPTIONS, faceX, faceY, 24)) {
				context.fill(faceX, faceY, faceX + 24, faceY + 24, 0xFF303A48);
				TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
				String initial = username.substring(0, 1).toUpperCase(Locale.ROOT);
				context.drawCenteredTextWithShadow(renderer, Text.literal(initial), faceX + 12, faceY + 8, 0xFFFFFFFF);
			}

			TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
			int textX = getX() + 43;
			int reserved = Math.max(58, renderer.getWidth(actionLabel) + 24);
			String shownName = renderer.trimToWidth(username, Math.max(24, getWidth() - 43 - reserved));
			context.drawTextWithShadow(renderer, Text.literal(shownName), textX, getY() + 8, 0xFFFFFFFF);
			context.drawTextWithShadow(renderer, Text.literal(subtitle), textX, getY() + 23, active ? MUTED : FAINT);

			int actionWidth = renderer.getWidth(actionLabel) + 14;
			int actionX = getX() + getWidth() - actionWidth - 9;
			context.fill(actionX, getY() + 11, actionX + actionWidth, getY() + 31, active ? 0xB025303B : 0x70232A33);
			context.drawTextWithShadow(renderer, Text.literal(actionLabel), actionX + 7, getY() + 17, active ? accent : FAINT);
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}
	}
}
