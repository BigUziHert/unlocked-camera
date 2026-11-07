package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.IronsArtificeAim;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "io.redspace.irons_artifice.client.RecoilManager", remap = false)
public abstract class IronsArtificeRecoilMixin {
    // Observe only Iron's contribution to the main camera, including a zero
    // delta once recoil settles. Other mods may also set the camera angles.
    @WrapMethod(method = "onComputeCameraAngles", require = 0)
    private static void unlockedcamera$observeRecoil(ViewportEvent.ComputeCameraAngles event,
                                                     Operation<Void> original) {
        float yaw = event.getYaw();
        float pitch = event.getPitch();
        original.call(event);
        if (event.getCamera() == event.getRenderer().getMainCamera()) {
            IronsArtificeAim.recordRenderedKick(event.getYaw() - yaw, event.getPitch() - pitch);
        }
    }
}
