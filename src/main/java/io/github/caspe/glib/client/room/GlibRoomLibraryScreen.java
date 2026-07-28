package io.github.caspe.glib.client.room;

import io.github.caspe.glib.api.client.editor.GlibWorkbenchScreen;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibScrollableRegion;
import io.github.caspe.glib.api.client.ui.GlibSearchField;
import io.github.caspe.glib.api.client.ui.GlibUi;
import io.github.caspe.glib.network.RoomLibrarySyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

@Environment(EnvType.CLIENT)
public final class GlibRoomLibraryScreen extends GlibWorkbenchScreen {
	private static final int ACCENT = 0xFF55D6BE;
	private static final int MUTED = 0xFF9EA7B3;
	private static final int ROW_HEIGHT = 44;

	private final GlibScrollableRegion scroll = new GlibScrollableRegion().scrollStep(ROW_HEIGHT);
	private GlibSearchField search;
	private GlibButton enterButton;
	private GlibButton leaveButton;
	private String selectedId;
	private long seenRevision = Long.MIN_VALUE;

	public GlibRoomLibraryScreen(Screen parent) {
		super(
			parent,
			Text.literal("Virtual Rooms"),
			Text.literal("Packet-only shared spaces from the server's live .gsave library"),
			ACCENT);
	}

	@Override
	protected void initWorkbench(GlibRect workspace) {
		GlibRect library = libraryRect(workspace);
		GlibRect inspector = inspectorRect(workspace);
		search = addDrawableChild(new GlibSearchField(
			textRenderer,
			library.x() + 10,
			library.y() + 10,
			library.width() - 106,
			24,
			Text.literal("Search virtual rooms")));
		addDrawableChild(new GlibButton(
			library.x() + library.width() - 88,
			library.y() + 10,
			78,
			24,
			Text.literal("Refresh"),
			ACCENT,
			ClientRoomLibrary::requestRefresh));
		enterButton = addDrawableChild(new GlibButton(
			inspector.x() + 12,
			inspector.y() + inspector.height() - 38,
			Math.max(80, inspector.width() - 122),
			26,
			Text.literal("Enter Room"),
			ACCENT,
			this::enterSelected));
		leaveButton = addDrawableChild(new GlibButton(
			inspector.x() + inspector.width() - 102,
			inspector.y() + inspector.height() - 38,
			90,
			26,
			Text.literal("Leave"),
			0xFFFFC857,
			this::leaveRoom));
		ClientRoomLibrary.requestRefresh();
		refreshSelection();
	}

	@Override
	protected void renderWorkbench(
			DrawContext context, GlibRect workspace, int mouseX, int mouseY, float delta) {
		if (seenRevision != ClientRoomLibrary.revision()) {
			seenRevision = ClientRoomLibrary.revision();
			refreshSelection();
		}
		GlibRect library = libraryRect(workspace);
		GlibRect inspector = inspectorRect(workspace);
		drawWorkbenchPanel(context, library.x(), library.y(), library.width(), library.height());
		drawWorkbenchPanel(context, inspector.x(), inspector.y(), inspector.width(), inspector.height());
		renderLibrary(context, library, mouseX, mouseY);
		renderInspector(context, inspector);
		enterButton.active = selected() != null && client != null && client.player != null;
		leaveButton.active =
			client != null && client.player != null && ClientRoomOverlay.isActive();
	}

	private void renderLibrary(DrawContext context, GlibRect library, int mouseX, int mouseY) {
		List<RoomLibrarySyncPayload.Entry> entries = filtered();
		GlibRect viewport = listRect(library);
		int contentHeight = entries.size() * ROW_HEIGHT;
		scroll.clamp(viewport, contentHeight);
		context.enableScissor(
			viewport.x(), viewport.y(),
			viewport.x() + viewport.width(), viewport.y() + viewport.height());
		for (int index = 0; index < entries.size(); index++) {
			RoomLibrarySyncPayload.Entry entry = entries.get(index);
			int y = viewport.y() + index * ROW_HEIGHT - scroll.scrollOffset();
			if (y + ROW_HEIGHT <= viewport.y() || y >= viewport.y() + viewport.height()) {
				continue;
			}
			GlibRect row = new GlibRect(viewport.x(), y, viewport.width() - 5, ROW_HEIGHT - 4);
			boolean selected = entry.id().equals(selectedId);
			boolean hovered = row.contains(mouseX, mouseY);
			int fill = selected ? 0xE02B3B42 : hovered ? 0xE025303B : 0xC01B232D;
			int border = selected ? ACCENT : hovered ? 0xA06C7A88 : 0x60394655;
			GlibUi.drawPanel(context, row.x(), row.y(), row.width(), row.height(), fill, border);
			context.fill(row.x(), row.y(), row.x() + 3, row.y() + row.height(),
				selected ? ACCENT : 0x504A5967);
			String title = textRenderer.trimToWidth(
				GlibUi.singleLine(entry.name()), row.width() - 22);
			context.drawTextWithShadow(
				textRenderer, Text.literal(title), row.x() + 11, row.y() + 7, 0xFFFFFFFF);
			String detail = String.format(
				Locale.ROOT, "%s  %dx%dx%d", entry.id(), entry.width(), entry.height(), entry.depth());
			context.drawTextWithShadow(
				textRenderer,
				Text.literal(textRenderer.trimToWidth(detail, row.width() - 22)),
				row.x() + 11,
				row.y() + 23,
				MUTED);
		}
		context.disableScissor();
		scroll.drawScrollbar(context, viewport, contentHeight, ACCENT);
		if (entries.isEmpty()) {
			String message = ClientRoomLibrary.requestAttempted() && !ClientRoomLibrary.supported()
				? "This server does not provide G'Lib virtual rooms."
				: ClientRoomLibrary.received()
				? "No saved rooms match this search."
				: "Waiting for the server room library...";
			context.drawCenteredTextWithShadow(
				textRenderer,
				Text.literal(message),
				viewport.x() + viewport.width() / 2,
				viewport.y() + 20,
				MUTED);
		}
	}

	private void renderInspector(DrawContext context, GlibRect inspector) {
		RoomLibrarySyncPayload.Entry entry = selected();
		int x = inspector.x() + 16;
		int y = inspector.y() + 16;
		if (entry == null) {
			context.drawTextWithShadow(
				textRenderer, Text.literal("Select a saved room"), x, y, 0xFFFFFFFF);
			context.drawTextWithShadow(
				textRenderer,
				Text.literal("Rooms update here as .gsave files change on the server."),
				x,
				y + 20,
				MUTED);
			return;
		}
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(textRenderer.trimToWidth(entry.name(), inspector.width() - 32)),
			x,
			y,
			0xFFFFFFFF);
		context.drawTextWithShadow(
			textRenderer, Text.literal(entry.id()), x, y + 17, ACCENT);
		if (ClientRoomLibrary.truncated()) {
			context.drawTextWithShadow(
				textRenderer,
				Text.literal("Showing " + ClientRoomLibrary.rooms().size()
					+ " of " + ClientRoomLibrary.totalRooms() + " rooms"),
				x,
				y + 34,
				0xFFFFC857);
		}
		metric(context, x, y + 52, "Size",
			entry.width() + " \u00D7 " + entry.height() + " \u00D7 " + entry.depth());
		metric(context, x, y + 82, "Visual blocks", Integer.toString(entry.blockCount()));
		metric(context, x, y + 112, "Markers", Integer.toString(entry.markerCount()));
		context.drawTextWithShadow(
			textRenderer,
			Text.literal("Entering creates a verified-empty, non-destructive host session."),
			x,
			Math.min(y + 154, inspector.y() + inspector.height() - 60),
			MUTED);
	}

	private void metric(DrawContext context, int x, int y, String label, String value) {
		context.drawTextWithShadow(textRenderer, Text.literal(label), x, y, MUTED);
		context.drawTextWithShadow(textRenderer, Text.literal(value), x + 104, y, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button != 0) {
			return false;
		}
		GlibRect viewport = listRect(libraryRect(workspaceRect()));
		if (!viewport.contains(mouseX, mouseY)) {
			return false;
		}
		int index = ((int) mouseY - viewport.y() + scroll.scrollOffset()) / ROW_HEIGHT;
		List<RoomLibrarySyncPayload.Entry> entries = filtered();
		if (index >= 0 && index < entries.size()) {
			selectedId = entries.get(index).id();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(
			double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		GlibRect viewport = listRect(libraryRect(workspaceRect()));
		if (scroll.handleMouseScrolled(
				viewport, filtered().size() * ROW_HEIGHT, mouseX, mouseY, verticalAmount)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void enterSelected() {
		RoomLibrarySyncPayload.Entry selected = selected();
		if (selected == null || client == null || client.player == null) {
			return;
		}
		client.player.networkHandler.sendChatCommand("glib room enter " + selected.id());
		client.setScreen(null);
	}

	private void leaveRoom() {
		if (client == null || client.player == null) {
			return;
		}
		client.player.networkHandler.sendChatCommand("glib room leave");
		client.setScreen(null);
	}

	private List<RoomLibrarySyncPayload.Entry> filtered() {
		if (search == null) {
			return ClientRoomLibrary.rooms();
		}
		return ClientRoomLibrary.rooms().stream()
			.filter(entry -> search.matches(entry.id() + " " + entry.name()))
			.toList();
	}

	private RoomLibrarySyncPayload.Entry selected() {
		if (selectedId == null) {
			return null;
		}
		return ClientRoomLibrary.rooms().stream()
			.filter(entry -> entry.id().equals(selectedId))
			.findFirst()
			.orElse(null);
	}

	private void refreshSelection() {
		if (selected() == null) {
			selectedId = ClientRoomLibrary.rooms().stream()
				.findFirst()
				.map(RoomLibrarySyncPayload.Entry::id)
				.orElse(null);
		}
	}

	private static GlibRect libraryRect(GlibRect workspace) {
		int width = Math.max(230, Math.min(390, workspace.width() * 2 / 5));
		return new GlibRect(workspace.x(), workspace.y(), width, workspace.height());
	}

	private static GlibRect inspectorRect(GlibRect workspace) {
		GlibRect library = libraryRect(workspace);
		return new GlibRect(
			library.x() + library.width() + 10,
			workspace.y(),
			Math.max(1, workspace.width() - library.width() - 10),
			workspace.height());
	}

	private static GlibRect listRect(GlibRect library) {
		return new GlibRect(
			library.x() + 10,
			library.y() + 44,
			library.width() - 20,
			library.height() - 54);
	}
}
