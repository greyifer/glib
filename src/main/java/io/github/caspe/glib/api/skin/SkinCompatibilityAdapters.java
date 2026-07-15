package io.github.caspe.glib.api.skin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

public final class SkinCompatibilityAdapters {
	private static final List<Adapter> KNOWN = List.of(
		new Adapter("trinkets", "Trinkets", AdapterKind.ACCESSORY),
		new Adapter("figura", "Figura", AdapterKind.AVATAR),
		new Adapter("iris", "Iris", AdapterKind.RENDERER),
		new Adapter("sodium", "Sodium", AdapterKind.RENDERER),
		new Adapter("cosmetica", "Cosmetica", AdapterKind.COSMETIC),
		new Adapter("skinlayers3d", "3D Skin Layers", AdapterKind.COSMETIC),
		new Adapter("showmeyourskin", "Show Me Your Skin", AdapterKind.COSMETIC),
		new Adapter("essential", "Essential", AdapterKind.COSMETIC)
	);

	private SkinCompatibilityAdapters() {
	}

	public static boolean isLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(normalize(modId));
	}

	public static boolean trinketsLoaded() {
		return isLoaded("trinkets");
	}

	public static boolean figuraLoaded() {
		return isLoaded("figura");
	}

	public static boolean irisLoaded() {
		return isLoaded("iris");
	}

	public static boolean sodiumLoaded() {
		return isLoaded("sodium");
	}

	public static Optional<Adapter> knownAdapter(String modId) {
		String id = normalize(modId);
		return KNOWN.stream().filter(adapter -> adapter.modId().equals(id)).findFirst();
	}

	public static List<Adapter> knownAdapters() {
		return KNOWN;
	}

	public static List<Adapter> loadedAdapters() {
		return KNOWN.stream().filter(Adapter::loaded).toList();
	}

	public static SkinEvents.Subscription runIfLoaded(String modId, Runnable installer) {
		if (isLoaded(modId)) {
			installer.run();
		}
		return () -> {
		};
	}

	private static String normalize(String modId) {
		if (modId == null) {
			return "";
		}
		return modId.toLowerCase(Locale.ROOT);
	}

	public record Adapter(String modId, String displayName, AdapterKind kind) {
		public boolean loaded() {
			return SkinCompatibilityAdapters.isLoaded(modId);
		}
	}

	public enum AdapterKind {
		ACCESSORY,
		AVATAR,
		RENDERER,
		COSMETIC
	}
}
