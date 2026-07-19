package io.github.caspe.glib.client.social;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.api.client.ui.GlibButton;
import io.github.caspe.glib.api.client.ui.GlibRect;
import io.github.caspe.glib.api.client.ui.GlibUi;
import io.github.caspe.glib.api.skin.FaceOptions;
import io.github.caspe.glib.api.skin.SkinRenderers;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibChatScreen extends Screen {
	private static final Gson GSON = new Gson();
	private static final int BACKDROP = 0x76060A10;
	private static final int PANEL = 0xF211161E;
	private static final int SURFACE = 0xE51A212C;
	private static final int LINE = 0x70404B5C;
	private static final int ACCENT = 0xFF4CDB8A;
	private static final int MUTED = 0xFF939EAC;
	private static final int DANGER = 0xFFFF718D;
	private static final FaceOptions FACE_OPTIONS = FaceOptions.ofSize(28);

	private final Screen parent;
	private final Player friend;
	private List<ChatMessage> messages = List.of();
	private TextFieldWidget messageField;
	private String status = "Loading conversation...";
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
		int inputY = panel.y() + panel.height() - 42;
		messageField = new TextFieldWidget(textRenderer, panel.x() + 16, inputY, panel.width() - 112, 24, Text.literal("Message"));
		messageField.setMaxLength(500);
		messageField.setPlaceholder(Text.literal("Write a message"));
		addDrawableChild(messageField);
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 90, inputY, 74, 24, Text.literal("Send"), ACCENT, this::send));
		addDrawableChild(new GlibButton(panel.x() + panel.width() - 44, panel.y() + 17, 28, 22,
			Text.literal("X"), 0xFFFFC857, this::close));
		blockButton = addDrawableChild(new GlibButton(panel.x() + panel.width() - 116, panel.y() + 17, 66, 22,
			Text.literal(confirmBlock ? "Confirm" : "Block"), DANGER, this::block));
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
			status = friend.online() ? "Online" : "Offline - messages will be waiting when they return";
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
			status = "Blocking also removes this friendship. Press Confirm to continue.";
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
		context.fill(0, 0, width, height, BACKDROP);
		GlibUi.drawPanel(context, panel.x(), panel.y(), panel.width(), panel.height(), PANEL, LINE);
		context.fill(panel.x(), panel.y(), panel.x() + 3, panel.y() + panel.height(), friend.online() ? ACCENT : MUTED);

		int faceX = panel.x() + 16;
		int faceY = panel.y() + 13;
		if (!SkinRenderers.drawPlayerFace(context, new GameProfile(friend.playerUuid(), friend.username()), FACE_OPTIONS, faceX, faceY, 28)) {
			context.fill(faceX, faceY, faceX + 28, faceY + 28, 0xFF303A48);
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(friend.username().substring(0, 1).toUpperCase(Locale.ROOT)), faceX + 14, faceY + 10, 0xFFFFFFFF);
		}
		context.drawTextWithShadow(textRenderer, Text.literal(friend.username()), panel.x() + 54, panel.y() + 15, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, panel.width() - 190)), panel.x() + 54, panel.y() + 30,
			isError(status) ? DANGER : MUTED);
		context.fill(panel.x() + 16, panel.y() + 52, panel.x() + panel.width() - 16, panel.y() + 53, LINE);

		GlibRect history = new GlibRect(panel.x() + 16, panel.y() + 62, panel.width() - 32, panel.height() - 116);
		renderMessages(context, history);
		super.render(context, mouseX, mouseY, delta);
	}

	private void renderMessages(DrawContext context, GlibRect history) {
		if (messages.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("No messages yet"), history.x() + history.width() / 2,
				history.y() + history.height() / 2 - 7, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("Say hello when you are ready."), history.x() + history.width() / 2,
				history.y() + history.height() / 2 + 9, MUTED);
			return;
		}

		int bubbleMaxWidth = Math.max(120, (int) (history.width() * 0.72F));
		int cursorY = history.y() + history.height();
		for (int index = messages.size() - 1; index >= 0; index--) {
			ChatMessage message = messages.get(index);
			List<OrderedText> lines = textRenderer.wrapLines(Text.literal(message.body()), bubbleMaxWidth - 18);
			int textWidth = 0;
			for (OrderedText line : lines) textWidth = Math.max(textWidth, textRenderer.getWidth(line));
			int bubbleWidth = Math.max(52, Math.min(bubbleMaxWidth, textWidth + 18));
			int bubbleHeight = lines.size() * 11 + 16;
			cursorY -= bubbleHeight;
			if (cursorY < history.y()) break;
			boolean mine = message.recipientUuid().equals(friend.playerUuid());
			int bubbleX = mine ? history.x() + history.width() - bubbleWidth : history.x();
			int bubbleColor = mine ? 0xDF1D3A32 : SURFACE;
			int edgeColor = mine ? ACCENT : 0xFF66717F;
			context.fill(bubbleX, cursorY, bubbleX + bubbleWidth, cursorY + bubbleHeight, bubbleColor);
			context.fill(mine ? bubbleX + bubbleWidth - 2 : bubbleX, cursorY,
				mine ? bubbleX + bubbleWidth : bubbleX + 2, cursorY + bubbleHeight, edgeColor);
			int textY = cursorY + 8;
			for (OrderedText line : lines) {
				context.drawTextWithShadow(textRenderer, line, bubbleX + 9, textY, 0xFFFFFFFF);
				textY += 11;
			}
			cursorY -= 7;
		}
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
		int panelWidth = Math.min(680, width - 24);
		int panelHeight = Math.min(450, height - 24);
		return new GlibRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	private static boolean isError(String value) {
		if (value == null) return false;
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.contains("failed") || lower.contains("invalid") || lower.contains("could not") || lower.contains("error");
	}
}
