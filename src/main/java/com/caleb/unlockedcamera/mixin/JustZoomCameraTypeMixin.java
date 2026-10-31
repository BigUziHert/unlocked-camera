package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Just Zoom must magnify the unlocked view without making it a first-person camera. */
@Mixin(CameraType.class)
public abstract class JustZoomCameraTypeMixin {
    @Shadow @Final private boolean firstPerson;

    /**
     * Just Zoom 2.1.0 answers true at HEAD while its zoom key is held, even for
     * THIRD_PERSON_BACK. Wrap the completed method, including its early return:
     * camera setup, player rendering, and Better Third Person must all agree
     * that it remains detached. Just Zoom still applies its FOV and sensitivity.
     * Keeping the enum's actual flag also respects Sable's added camera types.
     */
    @WrapMethod(method = "isFirstPerson")
    private boolean unlockedcamera$preserveZoomPerspective(Operation<Boolean> original) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null
                && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && UnlockedCameraClient.isUnlockedCameraActive()) {
            return this.firstPerson;
        }
        return original.call();
    }
}
