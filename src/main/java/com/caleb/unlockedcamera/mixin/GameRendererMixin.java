package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    public abstract void pick(float partialTicks);

    @Unique
    private boolean unlockedcamera$pickDeferred;
    @Unique
    private float unlockedcamera$deferredPartialTick;

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

    /**
     * renderLevel picks BEFORE Camera#setup, so a camera-ray pick there reads
     * the previous frame's camera pose and the block outline trails the
     * displayed camera by a frame (visible at low fps). While the crosshair
     * aim is active, skip the pick here and run it right after setup instead
     * — the same single pick per frame, just after the camera it depends on
     * exists. Camera#setup itself is untouched, so nothing smooths twice, and
     * Create's BigOutlines (TAIL of pick(F)) still runs on the fresh result.
     * A WrapOperation so it composes with Sable's wrapper on this same call
     * (which pushes the frame's interpolated sublevel poses around the pick —
     * the deferred call re-creates that, see SableCompat#withRenderPoses).
     *
     * <p>Residual: the {@code shouldRenderBlockOutline()} local between the
     * two points reads the previous frame's hit; it only gates outlines for
     * players who cannot build (adventure mode), and by one frame.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V"))
    private void unlockedcamera$deferRenderPick(GameRenderer renderer, float partialTick, Operation<Void> original) {
        if (UnlockedCameraClient.shouldDeferRenderPick()) {
            unlockedcamera$pickDeferred = true;
            unlockedcamera$deferredPartialTick = partialTick;
            return;
        }
        unlockedcamera$pickDeferred = false;
        original.call(renderer, partialTick);
    }

    @Inject(
            method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
                    shift = At.Shift.AFTER))
    private void unlockedcamera$deferredRenderPick(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!unlockedcamera$pickDeferred) {
            return;
        }
        unlockedcamera$pickDeferred = false;
        float partialTick = unlockedcamera$deferredPartialTick;
        UnlockedCameraClient.runDeferredRenderPick(partialTick, () -> this.pick(partialTick));
    }
}
