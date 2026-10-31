package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.JustZoomCompat;
import com.caleb.unlockedcamera.client.ClientConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Preserve lens zoom even when another mod returns before Just Zoom's FOV callback. */
@Mixin(GameRenderer.class)
public abstract class JustZoomFovMixin {
    @WrapMethod(method = "getFov")
    private double unlockedcamera$finishZoomLens(Camera camera, float partialTick, boolean useFovSetting,
                                                Operation<Double> original) {
        if (!ClientConfig.TOGGLE_MOD.get() || !JustZoomCompat.isNonSmoothZooming()) {
            return original.call(camera, partialTick, useFovSetting);
        }
        long readsBefore = JustZoomCompat.fovModifierReads();
        double fov = original.call(camera, partialTick, useFovSetting);
        return JustZoomCompat.finishNonSmoothFov(fov, readsBefore);
    }
}
