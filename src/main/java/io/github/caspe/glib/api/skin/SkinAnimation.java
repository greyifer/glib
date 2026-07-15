package io.github.caspe.glib.api.skin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface SkinAnimation {
	boolean tick(MutableSkinTexture texture, int ageTicks);
}
