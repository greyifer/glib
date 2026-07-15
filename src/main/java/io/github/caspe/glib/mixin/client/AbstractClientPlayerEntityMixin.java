package io.github.caspe.glib.mixin.client;

import io.github.caspe.glib.api.skin.SkinCompatibilityHooks;
import io.github.caspe.glib.api.skin.SkinOverrideRegistry;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
	@Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
	private void glib$useSkinOverride(CallbackInfoReturnable<SkinTextures> cir) {
		AbstractClientPlayerEntity player = (AbstractClientPlayerEntity) (Object) this;
		SkinOverrideRegistry.getTextures(player.getUuid()).ifPresent(textures -> {
			SkinCompatibilityHooks.fireThirdPerson(player.getUuid(), textures);
			cir.setReturnValue(textures);
		});
	}
}
