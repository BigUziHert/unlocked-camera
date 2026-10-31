package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.JustZoomCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Detect the actual Just Zoom lens callback without changing its behavior or cached values. */
@Pseudo
@Mixin(targets = "de.keksuccino.justzoom.ZoomHandler", remap = false)
public abstract class JustZoomFovObserverMixin {
    @Inject(method = "getFovModifier", at = @At("HEAD"), require = 0)
    private static void unlockedcamera$observeFovModifier(CallbackInfoReturnable<Float> cir) {
        JustZoomCompat.recordFovModifierRead();
    }
}
