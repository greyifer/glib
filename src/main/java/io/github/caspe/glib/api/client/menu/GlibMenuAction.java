package io.github.caspe.glib.api.client.menu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public record GlibMenuAction(String id, Text label, int accentColor, Runnable action) {
	public GlibMenuAction {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (label == null) {
			throw new IllegalArgumentException("label must not be null");
		}
		if (action == null) {
			throw new IllegalArgumentException("action must not be null");
		}
	}
}
