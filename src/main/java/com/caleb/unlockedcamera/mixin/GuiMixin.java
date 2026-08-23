package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Gui.class)
public abstract class GuiMixin {
    /**
     * Vanilla only draws the crosshair in first person; let it also draw while the
     * unlocked camera wants one (config-gated). Wrapping instead of redirecting
     * keeps this compatible with other mods hooking the same check.
     */
    @WrapOperation(
            method = "renderCrosshair",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean unlockedcamera$crosshairInUnlockedCamera(CameraType instance, Operation<Boolean> original) {
        return original.call(instance) || UnlockedCameraClient.shouldForceCrosshair();
    }
}
