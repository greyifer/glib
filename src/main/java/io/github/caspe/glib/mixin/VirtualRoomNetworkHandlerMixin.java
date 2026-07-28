package io.github.caspe.glib.mixin;

import io.github.caspe.glib.room.RoomRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A virtual floor is intentionally air in the real world, so it must not count as
 * unsupported flight for the vanilla floating kick.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class VirtualRoomNetworkHandlerMixin {
	@Shadow
	public ServerPlayerEntity player;

	@Inject(method = "isEntityOnAir", at = @At("HEAD"), cancellable = true)
	private void glib$virtualRoomIsNotFloating(
			Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity == player && RoomRuntime.isActive(player)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
	private void glib$blockRoomItemDrops(PlayerActionC2SPacket packet, CallbackInfo ci) {
		if (!glib$blockRoomWorldActions()) {
			return;
		}
		if (packet.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM
				|| packet.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS) {
			player.currentScreenHandler.updateToClient();
			ci.cancel();
		}
	}

	@Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
	private void glib$blockRoomInventoryDrops(ClickSlotC2SPacket packet, CallbackInfo ci) {
		if (glib$blockRoomWorldActions()
				&& (packet.getActionType() == SlotActionType.THROW || packet.getSlot() == -999)) {
			player.currentScreenHandler.updateToClient();
			ci.cancel();
		}
	}

	@Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
	private void glib$blockRoomCreativeDrops(
			CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
		if (glib$blockRoomWorldActions() && packet.slot() < 0) {
			ci.cancel();
		}
	}

	@Inject(method = "onUpdateSign", at = @At("HEAD"), cancellable = true)
	private void glib$blockRoomSignEdits(UpdateSignC2SPacket packet, CallbackInfo ci) {
		if (glib$blockRoomWorldActions()) {
			ci.cancel();
		}
	}

	private boolean glib$blockRoomWorldActions() {
		return player.getServer().isOnThread() && RoomRuntime.isActive(player);
	}
}
