package io.github.caspe.glib.api.client.menu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public record GlibMenuEntry(String id, Text label, int accentColor, int order, ScreenFactory screenFactory) {
	public GlibMenuEntry {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (label == null) {
			throw new IllegalArgumentException("label must not be null");
		}
		if (screenFactory == null) {
			throw new IllegalArgumentException("screenFactory must not be null");
		}
	}

	@FunctionalInterface
	public interface ScreenFactory {
		Screen create(Screen parent);
	}
}
