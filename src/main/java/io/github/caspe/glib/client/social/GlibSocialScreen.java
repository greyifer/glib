package io.github.caspe.glib.client.social;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibEditorTabs;
import io.github.caspe.glib.api.client.ui.GlibLoadingScreen;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibScrollableRegion;
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
	private static final int BACKDROP = 0x7805090E;
	private static final int PANEL = 0xF410151D;
	private static final int SURFACE = 0xE61A212B;
	private static final int SURFACE_HOVER = 0xF1242E3A;
	private static final int LINE = 0x70424C5B;
	private static final int ACCENT = 0xFF4CDB8A;
	private static final int BLUE = 0xFF67A8FF;
	private static final int MUTED = 0xFF98A3B1;
	private static final int FAINT = 0xFF687483;
	private static final int DANGER = 0xFFFF718D;
	private static final int ROW_HEIGHT = 42;
	private static final int ROW_GAP = 6;
	private static final FaceOptions FACE_OPTIONS = FaceOptions.ofSize(24);

	private final Screen parent;
	private final List<PlacedWidget> rowWidgets = new ArrayList<>();
	private final GlibScrollableRegion rowScroll = new GlibScrollableRegion().scrollStep(ROW_HEIGHT + ROW_GAP);
	private final GlibEditorTabs<Section> tabs;
	private final GlibLoadingScreen networkLoading = new GlibLoadingScreen(ACCENT, 0xFF101722, 0.14F, 30.0F);
	private Snapshot snapshot;
	private Section section;
	private TextFieldWidget searchField;
	private String status;
	private String searchQuery = "";
	private List<PlayerSearchResult> searchResults = List.of();
	private long searchDueAt;
	private int searchRevision;
	private int renderedRows;
	private boolean loading;
	private boolean searching;
	private AutoCloseable eventSubscription;

	public GlibSocialScreen(Screen parent) {
		this(parent, null, "Connecting securely...", Section.FRIENDS);
	}

	private GlibSocialScreen(Screen parent, Snapshot snapshot, String status, Section section) {
		super(Text.literal("G'Network Social"));
		this.parent = parent;
		this.snapshot = snapshot;
		this.status = status;
		this.section = section;
		this.tabs = new GlibEditorTabs<>(List.of(Section.FRIENDS, Section.REQUESTS), Section::label,
			section, ACCENT, LINE, 0.12F, 0.20F);
	}

	@Override
	protected void init() {
		rowWidgets.clear();
		rowScroll.reset();
		tabs.switchImmediately(section);
		GlibRect panel = panelRect();
		GlibRect inner = innerRect(panel);

		addDrawableChild(new GlibButton(panel.x() + panel.width() - 106, panel.y() + 16, 58, 22,
			Text.literal("Settings"), MUTED, this::openSettings));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 40, panel.y() + 16, 24, 22,
			Text.literal("X"), 0xFFFFC857, this::close));

		searchField = new TextFieldWidget(textRenderer, inner.x(), panel.y() + 52, inner.width(), 24, Text.literal("Search G'Lib players"));
		searchField.setMaxLength(16);
		searchField.setPlaceholder(Text.literal("Search players"));
		searchField.setText(searchQuery);
		searchField.setChangedListener(this::searchChanged);
		addDrawableChild(searchField);

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
		Section previous = tabs.activeTab();
		tabs.tick();
		if (tabs.activeTab() != previous) {
			section = tabs.activeTab();
			rebuildRows();
		}
		if (networkLoading.finished()) networkLoading.cancel();
		if (searchDueAt > 0L && Util.getMeasuringTimeMs() >= searchDueAt) {
			searchDueAt = 0L;
			startSearch(searchQuery, searchRevision);
		}
		positionRows();
	}

	private void searchChanged(String value) {
		searchQuery = value.trim();
		searchRevision++;
		searchDueAt = 0L;
		searching = false;
		searchResults = List.of();
		if (!loading) networkLoading.cancel();
		if (validSearch(searchQuery)) searchDueAt = Util.getMeasuringTimeMs() + 275L;
		rebuildRows();
	}

	private void startSearch(String query, int revision) {
		if (!validSearch(query) || client == null) return;
		searching = true;
		beginLoading("Finding players...");
		rebuildRows();
		GlibSocialClient.INSTANCE.searchPlayers(query).whenComplete((results, error) -> client.execute(() -> {
			if (client.currentScreen != this || revision != searchRevision || !searchQuery.equals(query)) return;
			searching = false;
			finishLoading();
			if (error != null) {
				status = SocialScreenSupport.message(error);
				searchResults = List.of();
			} else {
				searchResults = results;
				status = results.isEmpty() ? "No matching G'Lib players." : results.size() + " player" + (results.size() == 1 ? "" : "s") + " found.";
			}
			rebuildRows();
		}));
	}

	private void rebuildRows() {
		for (PlacedWidget placed : rowWidgets) remove(placed.widget());
		rowWidgets.clear();
		rowScroll.reset();
		renderedRows = 0;
		if (client == null) return;
		if (validSearch(searchQuery)) buildSearchRows();
		else if (snapshot != null && section == Section.FRIENDS) buildFriendRows();
		else if (snapshot != null) buildRequestRows();
		positionRows();
	}

	private void buildFriendRows() {
		for (Player friend : snapshot.friends()) {
			addRow(renderedRows++, friend.playerUuid(), friend.username(), friend.online() ? "Online" : "Offline",
				friend.online() ? ACCENT : MUTED, "Message", () -> openChat(friend), null, MUTED, null);
		}
	}

	private void buildRequestRows() {
		for (FriendRequest request : snapshot.incoming()) {
			addRow(renderedRows++, request.player().playerUuid(), request.player().username(), "Wants to be friends", ACCENT,
				"Accept", () -> runAction(GlibSocialClient.INSTANCE.acceptRequest(request.requestId()), "You are now friends."),
				"Decline", DANGER, () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Request declined."));
		}
		for (FriendRequest request : snapshot.outgoing()) {
			addRow(renderedRows++, request.player().playerUuid(), request.player().username(), "Request sent", MUTED,
				"Pending", null, "Cancel", DANGER,
				() -> runAction(GlibSocialClient.INSTANCE.deleteRequest(request.requestId()), "Request cancelled."));
		}
	}

	private void buildSearchRows() {
		for (PlayerSearchResult result : searchResults) {
			switch (result.relationship()) {
				case "FRIEND" -> addRow(renderedRows++, result.playerUuid(), result.username(), "Already friends", ACCENT,
					"Message", () -> openChat(playerFor(result)), null, MUTED, null);
				case "INCOMING" -> addRow(renderedRows++, result.playerUuid(), result.username(), "Sent you a request", ACCENT,
					"Accept", result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.acceptRequest(result.requestId()), "You are now friends."),
					"Decline", DANGER, result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(result.requestId()), "Request declined."));
				case "OUTGOING" -> addRow(renderedRows++, result.playerUuid(), result.username(), "Request sent", MUTED,
					"Pending", null, "Cancel", DANGER,
					result.requestId() == null ? null : () -> runAction(GlibSocialClient.INSTANCE.deleteRequest(result.requestId()), "Request cancelled."));
				default -> addRow(renderedRows++, result.playerUuid(), result.username(), "G'Lib player", BLUE,
					"Add friend", () -> runAction(GlibSocialClient.INSTANCE.sendFriendRequest(result.username()), "Request sent to " + result.username() + "."),
					null, MUTED, null);
			}
		}
	}

	private void addRow(int slot, UUID playerUuid, String username, String subtitle, int accent, String actionLabel,
		Runnable action, String secondaryLabel, int secondaryAccent, Runnable secondaryAction) {
		GlibRect viewport = rowsViewport(panelRect());
		int offset = slot * (ROW_HEIGHT + ROW_GAP);
		int secondaryWidth = secondaryLabel == null ? 0 : 62;
		int primaryWidth = viewport.width() - (secondaryWidth == 0 ? 0 : secondaryWidth + 6) - 4;
		SocialRowButton row = new SocialRowButton(viewport.x(), viewport.y() + offset, primaryWidth, ROW_HEIGHT,
			playerUuid, username, subtitle, accent, actionLabel, action);
		addPlaced(row, offset, action != null);
		if (secondaryLabel != null) {
			GlibButton secondary = new GlibButton(viewport.x() + primaryWidth + 6, viewport.y() + offset, secondaryWidth, ROW_HEIGHT,
				Text.literal(secondaryLabel), secondaryAccent, secondaryAction == null ? () -> {} : secondaryAction);
			addPlaced(secondary, offset, secondaryAction != null);
		}
	}

	private void addPlaced(ClickableWidget widget, int offset, boolean baseActive) {
		addDrawableChild(widget);
		rowWidgets.add(new PlacedWidget(widget, offset, baseActive));
	}

	private void positionRows() {
		GlibRect viewport = rowsViewport(panelRect());
		for (PlacedWidget placed : rowWidgets) {
			ClickableWidget widget = placed.widget();
			widget.setY(viewport.y() + placed.offset() - rowScroll.scrollOffset());
			boolean visible = widget.getY() >= viewport.y() && widget.getY() + widget.getHeight() <= viewport.y() + viewport.height();
			widget.visible = visible;
			widget.active = visible && placed.baseActive() && !tabs.transitionActive() && !networkLoading.active();
		}
	}

	private void selectSection(Section next) {
		if (!searchField.getText().isEmpty()) searchField.setText("");
		tabs.select(next, 0.20F);
		positionRows();
	}

	void refresh() {
		if (loading || client == null) return;
		loading = true;
		beginLoading(snapshot == null ? "Connecting to G'Network..." : "Refreshing friends...");
		GlibSocialClient.INSTANCE.snapshot().whenComplete((loaded, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			finishLoading();
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			snapshot = loaded;
			status = "Online as " + loaded.me().username();
			rebuildRows();
			if (validSearch(searchQuery)) {
				searchRevision++;
				searchDueAt = Util.getMeasuringTimeMs() + 150L;
			}
		}));
	}

	private void runAction(CompletableFuture<Void> action, String success) {
		if (loading || client == null) return;
		loading = true;
		beginLoading("Updating friends...");
		action.whenComplete((ignored, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				finishLoading();
				status = SocialScreenSupport.message(error);
				return;
			}
			status = success;
			refresh();
		}));
	}

	private void beginLoading(String message) {
		networkLoading.start(message, 30.0F);
		positionRows();
	}

	private void finishLoading() {
		networkLoading.finish();
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
		GlibRect inner = innerRect(panel);
		GlibRect viewport = rowsViewport(panel);
		context.fill(0, 0, width, height, BACKDROP);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);

		context.fill(panel.x() + 16, panel.y() + 15, panel.x() + 40, panel.y() + 39, 0xFF20352E);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("G"), panel.x() + 28, panel.y() + 23, ACCENT);
		context.drawTextWithShadow(textRenderer, Text.literal("Social"), panel.x() + 48, panel.y() + 17, 0xFFFFFFFF);
		String identity = snapshot == null ? "Signing in..." : "Online as " + snapshot.me().username();
		context.drawTextWithShadow(textRenderer, Text.literal(identity), panel.x() + 48, panel.y() + 31, MUTED);

		tabs.renderTabs(context, textRenderer, this::tabRect, mouseX, mouseY);
		drawRequestBadge(context);
		context.fill(inner.x(), viewport.y() - 8, inner.x() + inner.width(), viewport.y() - 7, LINE);
		drawEmptyState(context, viewport);
		rowScroll.drawScrollbar(context, viewport, rowsContentHeight(), ACCENT);

		String shownStatus = textRenderer.trimToWidth(status == null ? "" : status, inner.width());
		context.drawTextWithShadow(textRenderer, Text.literal(shownStatus), inner.x(), panel.y() + panel.height() - 22,
			isError(status) ? DANGER : FAINT);
		super.render(context, mouseX, mouseY, delta);

		if (tabs.transitionActive()) tabs.renderTransition(context, textRenderer, viewport, "Opening ");
		if (networkLoading.active()) networkLoading.render(context, textRenderer, viewport);
	}

	private void drawRequestBadge(DrawContext context) {
		if (snapshot == null || snapshot.incoming().isEmpty()) return;
		GlibRect requests = tabRect(Section.REQUESTS);
		String label = Integer.toString(snapshot.incoming().size());
		int badgeWidth = Math.max(14, textRenderer.getWidth(label) + 8);
		int x = requests.x() + requests.width() - badgeWidth - 5;
		context.fill(x, requests.y() + 5, x + badgeWidth, requests.y() + 21, 0xD93B7D5E);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + badgeWidth / 2, requests.y() + 9, 0xFFFFFFFF);
	}

	private void drawEmptyState(DrawContext context, GlibRect viewport) {
		if (renderedRows > 0 || networkLoading.active() || tabs.transitionActive()) return;
		String primary;
		String secondary;
		if (searchQuery.length() == 1) {
			primary = "Keep typing";
			secondary = "Enter at least two characters.";
		} else if (!searchQuery.isEmpty() && !validSearch(searchQuery)) {
			primary = "That search cannot be used";
			secondary = "Use letters, numbers, or underscores.";
		} else if (validSearch(searchQuery) && !searching) {
			primary = "No matching players";
			secondary = "Only players signed into G'Network appear here.";
		} else if (snapshot == null) {
			primary = "Connecting";
			secondary = "Securely verifying your account.";
		} else if (section == Section.FRIENDS) {
			primary = "No friends yet";
			secondary = "Search above to find another G'Lib player.";
		} else {
			primary = "No pending requests";
			secondary = "Incoming and outgoing requests appear here.";
		}
		int centerX = viewport.x() + viewport.width() / 2;
		int centerY = viewport.y() + viewport.height() / 2;
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(primary), centerX, centerY - 10, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(secondary), centerX, centerY + 8, MUTED);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (tabs.handleClick(this::tabRect, mouseX, mouseY, button)) {
			selectSection(tabs.selectedTab());
			return true;
		}
		GlibRect viewport = rowsViewport(panelRect());
		if ((tabs.transitionActive() || networkLoading.active()) && viewport.contains(mouseX, mouseY)) return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		GlibRect viewport = rowsViewport(panelRect());
		if (rowScroll.handleMouseScrolled(viewport, rowsContentHeight(), mouseX, mouseY, verticalAmount)) {
			positionRows();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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

	private int rowsContentHeight() {
		return renderedRows == 0 ? 0 : renderedRows * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
	}

	private GlibRect tabRect(Section tab) {
		GlibRect panel = panelRect();
		int index = tab == Section.FRIENDS ? 0 : 1;
		return new GlibRect(panel.x() + 18 + index * 112, panel.y() + 84, 104, 26);
	}

	private GlibRect rowsViewport(GlibRect panel) {
		return new GlibRect(panel.x() + 18, panel.y() + 120, panel.width() - 36, panel.height() - 154);
	}

	private GlibRect innerRect(GlibRect panel) {
		return new GlibRect(panel.x() + 18, panel.y() + 14, panel.width() - 36, panel.height() - 28);
	}

	private GlibRect panelRect() {
		int panelWidth = Math.min(690, width - 24);
		int panelHeight = Math.min(440, height - 24);
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

	private enum Section implements GlibEditorTabs.Labelled {
		FRIENDS("Friends"),
		REQUESTS("Requests");

		private final String label;

		Section(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	private record PlacedWidget(ClickableWidget widget, int offset, boolean baseActive) {
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
			context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), GlibUi.mixColor(SURFACE, SURFACE_HOVER, hoverAmount));
			context.fill(getX(), getY(), getX() + 2, getY() + getHeight(), active ? accent : FAINT);
			context.fill(getX(), getY(), getX() + getWidth(), getY() + 1, LINE);

			int faceX = getX() + 9;
			int faceY = getY() + 9;
			TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
			if (!SkinRenderers.drawPlayerFace(context, new GameProfile(playerUuid, username), FACE_OPTIONS, faceX, faceY, 24)) {
				context.fill(faceX, faceY, faceX + 24, faceY + 24, 0xFF303A48);
				context.drawCenteredTextWithShadow(renderer, Text.literal(username.substring(0, 1).toUpperCase(Locale.ROOT)), faceX + 12, faceY + 8, 0xFFFFFFFF);
			}

			int textX = getX() + 43;
			int reserved = Math.max(60, renderer.getWidth(actionLabel) + 25);
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
