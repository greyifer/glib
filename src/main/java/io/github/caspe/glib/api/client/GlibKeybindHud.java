package io.github.caspe.glib.api.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public final class GlibKeybindHud {
	private static final int RIGHT_MARGIN = 10;
	private static final int BOTTOM_MARGIN = 8;
	private static final int ROW_HEIGHT = 11;
	private static final int ACTION_WIDTH = 104;
	private static final int COLUMN_GAP = 16;
	private static final Map<String, List<Line>> GROUPS = new ConcurrentHashMap<>();

	private GlibKeybindHud() {
	}

	public static void set(String group, List<Line> lines) {
		if (group == null || group.isBlank()) {
			throw new IllegalArgumentException("HUD group cannot be blank");
		}
		if (lines == null || lines.isEmpty()) {
			GROUPS.remove(group);
			return;
		}
		GROUPS.put(group, List.copyOf(lines));
	}

	public static void clear(String group) {
		if (group != null) {
			GROUPS.remove(group);
		}
	}

	public static void clearAll() {
		GROUPS.clear();
	}

	public static Line line(String action, KeyBinding key) {
		return new Line(() -> action, () -> key.getBoundKeyLocalizedText().getString());
	}

	public static Line line(Supplier<String> action, KeyBinding key) {
		return new Line(action, () -> key.getBoundKeyLocalizedText().getString());
	}

	public static boolean isPressedEvenIfConflicting(KeyBinding keyBinding) {
		return keyBinding != null && (keyBinding.isPressed() || isRawDown(keyBinding));
	}

	public static boolean isRawDown(KeyBinding keyBinding) {
		if (keyBinding == null || keyBinding.isUnbound()) {
			return false;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.currentScreen != null) {
			return false;
		}
		InputUtil.Key key = InputUtil.fromTranslationKey(keyBinding.getBoundKeyTranslationKey());
		long handle = client.getWindow().getHandle();
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
		}
		if (key.getCategory() == InputUtil.Type.KEYSYM) {
			return GLFW.glfwGetKey(handle, key.getCode()) == GLFW.GLFW_PRESS;
		}
		return false;
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer textRenderer = client.textRenderer;
		List<Line> lines = GROUPS.values().stream().flatMap(List::stream).toList();
		if (lines.isEmpty()) {
			return;
		}

		int keyWidth = 0;
		for (Line line : lines) {
			keyWidth = Math.max(keyWidth, textRenderer.getWidth(line.key()));
		}

		int actionX = context.getScaledWindowWidth() - ACTION_WIDTH - COLUMN_GAP - keyWidth - RIGHT_MARGIN;
		int keyX = actionX + ACTION_WIDTH + COLUMN_GAP;
		int y = context.getScaledWindowHeight() - lines.size() * ROW_HEIGHT - BOTTOM_MARGIN;
		for (Line line : lines) {
			context.drawTextWithShadow(textRenderer, line.action(), actionX, y, 0xFFE7EDF7);
			context.drawTextWithShadow(textRenderer, line.key(), keyX, y, 0xFFE7EDF7);
			y += ROW_HEIGHT;
		}
	}

	public record Line(Supplier<String> actionSupplier, Supplier<String> keySupplier) {
		private String action() {
			return actionSupplier.get();
		}

		private String key() {
			return keySupplier.get();
		}
	}

	public static final class TriggerKey {
		private final KeyBinding keyBinding;
		private boolean wasDown;

		public TriggerKey(KeyBinding keyBinding) {
			this.keyBinding = keyBinding;
		}

		public boolean wasPressed() {
			boolean pressed = false;
			while (keyBinding.wasPressed()) {
				pressed = true;
			}

			boolean down = isRawDown(keyBinding);
			if (down && !wasDown) {
				pressed = true;
			}
			wasDown = down;
			return pressed;
		}
	}
}
