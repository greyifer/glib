package io.github.caspe.glib.mixin.client;

import io.github.caspe.glib.api.entity.GlibEntityStates;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
	private static void glib$hideTaggedEntityShadow(MatrixStack matrices, VertexConsumerProvider vertices, Entity entity, float opacity, float tickDelta, WorldView world, float radius, CallbackInfo ci) {
		if (entity.getCommandTags().contains(GlibEntityStates.HIDE_SHADOW_TAG) || entity.getCommandTags().contains(GlibEntityStates.LEGACY_HIDE_SHADOW_TAG)) {
			ci.cancel();
		}
	}
}
