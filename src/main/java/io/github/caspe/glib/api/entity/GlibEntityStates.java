package io.github.caspe.glib.api.entity;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

/** Applies reusable editor state behavior without knowing any consuming mod's content model. */
public final class GlibEntityStates {
	public static final String MOVEMENT_LOCK_TAG = "glib_movement_locked";
	public static final String HIDE_SHADOW_TAG = "glib_hide_shadow";
	public static final String LEGACY_MOVEMENT_LOCK_TAG = "gambit_movement_locked";
	public static final String LEGACY_HIDE_SHADOW_TAG = "gambit_hide_shadow";

	private GlibEntityStates() {
	}

	public static void apply(LivingEntity entity, GlibEntityState state) {
		if (entity == null || state == null) {
			return;
		}
		if (entity instanceof MobEntity mob) {
			mob.setAiDisabled(!state.aiEnabled());
			mob.setPersistent();
		}
		if (state.movementLocked() && entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED) != null) {
			entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0D);
		}
		entity.setVelocity(Vec3d.ZERO);
		entity.setInvulnerable(state.invulnerable());
		setTag(entity, MOVEMENT_LOCK_TAG, state.movementLocked());
		setTag(entity, HIDE_SHADOW_TAG, !state.shadowVisible());
		entity.removeCommandTag(LEGACY_MOVEMENT_LOCK_TAG);
		entity.removeCommandTag(LEGACY_HIDE_SHADOW_TAG);
	}

	private static void setTag(LivingEntity entity, String tag, boolean enabled) {
		if (enabled) {
			entity.addCommandTag(tag);
		} else {
			entity.removeCommandTag(tag);
		}
	}
}
