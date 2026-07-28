package io.github.caspe.glib.mixin;

import com.google.common.collect.Iterables;
import io.github.caspe.glib.room.RoomRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.CollisionView;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supplies per-player collision from the packet-only room without changing the world.
 */
@Mixin(CollisionView.class)
public interface VirtualRoomCollisionMixin {
	@Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
	private void glib$virtualRoomCollisions(
			@Nullable Entity entity,
			Box box,
			CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
		if (entity instanceof ServerPlayerEntity player
				&& RoomRuntime.usesVirtualCollisions(player, box)) {
			cir.setReturnValue(Iterables.concat(
				cir.getReturnValue(),
				RoomRuntime.collisions(player, box)));
		}
	}
}
