package io.github.caspe.glib.api.client.menu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class GlibMenuRegistry {
	public static final String ENTRYPOINT = "glib_menu";
	private static final Map<String, GlibMenuEntry> ENTRIES = new LinkedHashMap<>();
	private static boolean contributorsLoaded;

	private GlibMenuRegistry() {
	}

	public static void register(String id, Text label, int accentColor, int order, GlibMenuEntry.ScreenFactory screenFactory) {
		register(new GlibMenuEntry(id, label, accentColor, order, screenFactory));
	}

	public static void register(GlibMenuEntry entry) {
		ENTRIES.put(entry.id(), entry);
	}

	public static List<GlibMenuEntry> entries() {
		List<GlibMenuEntry> entries = new ArrayList<>(ENTRIES.values());
		entries.sort(Comparator.comparingInt(GlibMenuEntry::order).thenComparing(GlibMenuEntry::id));
		return List.copyOf(entries);
	}

	public static void loadContributors() {
		if (contributorsLoaded) {
			return;
		}
		contributorsLoaded = true;
		for (GlibMenuContributor contributor : FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, GlibMenuContributor.class)) {
			contributor.register();
		}
	}

	public static Screen rootScreen(Screen parent) {
		loadContributors();
		return new GlibMenuScreen(parent);
	}
}
