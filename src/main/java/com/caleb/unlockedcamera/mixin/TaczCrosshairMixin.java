package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TACZ cancels the vanilla crosshair layer whenever a gun is in the main hand
 * and draws its own reticle instead — but that reticle returns early in third
 * person unless {@code ShoulderSurfingCompat.showCrosshair()} vouches for it
 * (false unless Shoulder Surfing is installed). With a gun in hand under this
 * mod's camera the result was no crosshair at all: vanilla's cancelled,
 * TACZ's suppressed.
 *
 * <p>Answer through that same seam — the one TACZ built for third-person
 * camera mods — so the gun's own reticle (with its spread/hit-marker feedback)
 * draws exactly when this mod's crosshair would. Aim correctness is separate
 * and comes from the rotation hold, which now recognizes guns.
 *
 * <p>Applied only when TACZ is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a TACZ update reshapes this compat
 * class, the game launches with the crosshair compat silently un-applied
 * instead of crashing.
 */
@Mixin(targets = "com.tacz.guns.compat.shouldersurfing.ShoulderSurfingCompat", remap = false)
public abstract class TaczCrosshairMixin {
    @Inject(method = "showCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private static void unlockedcamera$showGunCrosshair(CallbackInfoReturnable<Boolean> cir) {
        if (UnlockedCameraClient.taczShouldShowCrosshair()) {
            cir.setReturnValue(true);
        }
    }
}
