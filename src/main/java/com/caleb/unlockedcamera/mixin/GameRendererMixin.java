package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * While the shoulder offset is engaged, targeting runs along the camera ray
     * through the center crosshair instead of the player's eye ray, so what you
     * see under the crosshair is what you hit.
     */
    @Inject(
            method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void unlockedcamera$cameraRayPick(Entity entity, double blockInteractionRange, double entityInteractionRange, float partialTick, CallbackInfoReturnable<HitResult> cir) {
        HitResult overridden = UnlockedCameraClient.cameraRayPick(entity, blockInteractionRange, entityInteractionRange, partialTick);
        if (overridden != null) {
            cir.setReturnValue(overridden);
        }
    }
}
