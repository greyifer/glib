package io.github.caspe.glib.api.skin;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class SkinAnimator {
	private static final Set<ActiveAnimation> ACTIVE = ConcurrentHashMap.newKeySet();

	private SkinAnimator() {
	}

	public static void play(MutableSkinTexture texture, SkinAnimation animation) {
		if (!texture.isClosed()) {
			ACTIVE.add(new ActiveAnimation(texture, animation));
		}
	}

	public static void stop(MutableSkinTexture texture) {
		ACTIVE.removeIf(active -> active.texture == texture);
	}

	public static void tickAll() {
		Iterator<ActiveAnimation> iterator = ACTIVE.iterator();
		while (iterator.hasNext()) {
			ActiveAnimation active = iterator.next();
			if (active.texture.isClosed() || !active.animation.tick(active.texture, active.ageTicks++)) {
				iterator.remove();
			}
		}
	}

	private static final class ActiveAnimation {
		private final MutableSkinTexture texture;
		private final SkinAnimation animation;
		private int ageTicks;

		private ActiveAnimation(MutableSkinTexture texture, SkinAnimation animation) {
			this.texture = texture;
			this.animation = animation;
		}
	}
}
