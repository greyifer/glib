package io.github.caspe.glib.mixin.client;

import com.mojang.authlib.GameProfile;
import io.github.caspe.glib.api.skin.SkinOverrideRegistry;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
	@Shadow
	@Final
	private GameProfile profile;

	@Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
	private void glib$useSkinOverride(CallbackInfoReturnable<SkinTextures> cir) {
		if (profile.getId() != null) {
			SkinOverrideRegistry.getTextures(profile.getId()).ifPresent(cir::setReturnValue);
		}
	}
}
