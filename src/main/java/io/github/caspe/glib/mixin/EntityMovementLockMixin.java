package io.github.caspe.glib.mixin;

import io.github.caspe.glib.api.entity.GlibEntityStates;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

@Mixin(Entity.class)
public abstract class EntityMovementLockMixin {
	private static final Map<Entity, Vec3d> GLIB_ANCHORS = new WeakHashMap<>();

	@Inject(method = "tick", at = @At("TAIL"))
	private void glib$keepEntityAtAnchor(CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (!entity.getCommandTags().contains(GlibEntityStates.MOVEMENT_LOCK_TAG) && !entity.getCommandTags().contains(GlibEntityStates.LEGACY_MOVEMENT_LOCK_TAG)) {
			synchronized (GLIB_ANCHORS) {
				GLIB_ANCHORS.remove(entity);
			}
			return;
		}
		Vec3d anchor;
		synchronized (GLIB_ANCHORS) {
			anchor = GLIB_ANCHORS.computeIfAbsent(entity, ignored -> entity.getPos());
		}
		entity.setPosition(anchor.x, anchor.y, anchor.z);
		entity.setVelocity(Vec3d.ZERO);
	}
}
