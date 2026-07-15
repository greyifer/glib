package io.github.caspe.glib.api.client.menu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public final class GlibMenuKeybind {
	private static KeyBinding menuKey;

	private GlibMenuKeybind() {
	}

	public static void register() {
		if (menuKey != null) {
			return;
		}
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.glib.open_menu",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"key.categories.glib"
		));
	}

	public static KeyBinding keyBinding() {
		return menuKey;
	}

	public static void handle(MinecraftClient client) {
		if (menuKey == null) {
			return;
		}
		while (menuKey.wasPressed()) {
			if (client.currentScreen instanceof GlibMenuScreen) {
				continue;
			}
			client.setScreen(GlibMenuRegistry.rootScreen(client.currentScreen));
		}
	}
}
