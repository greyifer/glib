package io.github.caspe.glib.api.client.menu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public interface GlibMenuContributor {
	void register();
}
