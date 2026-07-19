package io.github.caspe.glib.client.social;

import com.google.gson.Gson;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibChatScreen extends Screen {
	private static final Gson GSON = new Gson();
	private static final int PANEL = 0xF0121720;
	private static final int PANEL_SOFT = 0xE0161D28;
	private static final int LINE = 0x80394655;
	private static final int ACCENT = 0xFF45D483;
	private static final int MUTED = 0xFF9EA7B3;
	private static final int DANGER = 0xFFFF718D;
	private final Screen parent;
	private final Player friend;
	private List<ChatMessage> messages = List.of();
	private TextFieldWidget messageField;
	private String status = "Loading messages...";
	private boolean loading;
	private boolean confirmBlock;
	private GlibButton blockButton;
	private AutoCloseable eventSubscription;

	public GlibChatScreen(Screen parent, Player friend) {
		super(Text.literal("Chat with " + friend.username()));
		this.parent = parent;
		this.friend = friend;
	}

	@Override
	protected void init() {
		GlibRect panel = panelRect();
		messageField = new TextFieldWidget(textRenderer, panel.x() + 18, panel.y() + panel.height() - 49, panel.width() - 118, 22, Text.literal("Message"));
		messageField.setMaxLength(500);
		messageField.setPlaceholder(Text.literal("Message " + friend.username()));
		addDrawableChild(messageField);
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 94, panel.y() + panel.height() - 49, 76, 22,
			Text.literal("Send"), ACCENT, this::send));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 58, panel.y() + 16, 40, 22,
			Text.literal("Back"), 0xFFFFC857, this::close));
		blockButton = addDrawableChild(new GlibButton(panel.x() + panel.width() - 150, panel.y() + 16, 86, 22,
			Text.literal("Block"), DANGER, this::block));
		if (eventSubscription == null) {
			eventSubscription = GlibSocialClient.INSTANCE.listen(event -> {
				if (event.type().equals("chat.message")) {
					try {
						ChatMessage message = GSON.fromJson(event.data(), ChatMessage.class);
						if (message.senderUuid().equals(friend.playerUuid()) || message.recipientUuid().equals(friend.playerUuid())) loadMessages();
					} catch (RuntimeException ignored) {
					}
				}
			});
		}
		loadMessages();
	}

	private void loadMessages() {
		if (loading || client == null) return;
		loading = true;
		GlibSocialClient.INSTANCE.messages(friend.playerUuid()).whenComplete((loaded, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			messages = loaded;
			status = friend.online() ? "Online" : "Offline — messages will be delivered later";
		}));
	}

	private void send() {
		String body = messageField.getText().trim();
		if (body.isEmpty() || loading || client == null) return;
		messageField.setText("");
		loading = true;
		GlibSocialClient.INSTANCE.sendMessage(friend.playerUuid(), body).whenComplete((message, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				messageField.setText(body);
				return;
			}
			loadMessages();
		}));
	}

	private void block() {
		if (!confirmBlock) {
			confirmBlock = true;
			blockButton.setMessage(Text.literal("Confirm"));
			status = "Blocking also removes this friendship. Click Confirm to continue.";
			return;
		}
		if (client == null) return;
		loading = true;
		GlibSocialClient.INSTANCE.block(friend.playerUuid()).whenComplete((ignored, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				status = SocialScreenSupport.message(error);
				return;
			}
			client.setScreen(parent);
		}));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		GlibRect panel = panelRect();
		context.fill(0, 0, width, height, 0x62060A10);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + panel.width(), panel.y() + 2, ACCENT);
		context.drawTextWithShadow(textRenderer, title, panel.x() + 18, panel.y() + 18, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(status), panel.x() + 18, panel.y() + 35, MUTED);
		GlibRect history = new GlibRect(panel.x() + 14, panel.y() + 54, panel.width() - 28, panel.height() - 114);
		GlibUi.drawPanel(context, history.x(), history.y(), history.width(), history.height(), PANEL_SOFT, LINE);
		List<RenderedLine> lines = new ArrayList<>();
		for (ChatMessage message : messages) {
			boolean mine = message.recipientUuid().equals(friend.playerUuid());
			String prefix = mine ? "You: " : friend.username() + ": ";
			for (OrderedText line : textRenderer.wrapLines(Text.literal(prefix + message.body()), history.width() - 16)) {
				lines.add(new RenderedLine(line, mine ? 0xFFBFEFFF : 0xFFFFFFFF));
			}
		}
		int maxRows = Math.max(1, (history.height() - 16) / 12);
		int start = Math.max(0, lines.size() - maxRows);
		int y = history.y() + 8;
		for (int index = start; index < lines.size(); index++) {
			RenderedLine line = lines.get(index);
			context.drawTextWithShadow(textRenderer, line.text(), history.x() + 8, y, line.color());
			y += 12;
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER && messageField != null && messageField.isFocused()) {
			send();
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
		int panelWidth = Math.min(640, width - 24);
		int panelHeight = Math.min(430, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private record RenderedLine(OrderedText text, int color) {
	}
}
