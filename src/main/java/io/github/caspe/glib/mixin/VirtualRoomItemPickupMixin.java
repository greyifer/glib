package io.github.caspe.glib.mixin;

import io.github.caspe.glib.room.RoomRuntime;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents packet-only room participants from consuming real host-world items.
 */
@Mixin(ItemEntity.class)
public abstract class VirtualRoomItemPickupMixin {
	@Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
	private void glib$blockVirtualRoomPickup(PlayerEntity player, CallbackInfo ci) {
		if (player instanceof ServerPlayerEntity serverPlayer && RoomRuntime.isActive(serverPlayer)) {
			ci.cancel();
		}
	}
}
