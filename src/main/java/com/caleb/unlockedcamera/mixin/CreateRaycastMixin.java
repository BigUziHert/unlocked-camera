package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's UI raycasts (value boxes, contraption controls, goggles, ...) all get
 * their ray direction from RaycastHelper#getTraceTarget, built from the player's
 * body rotation — which the shoulder offset decouples from the crosshair. While
 * the offset is engaged, aim those rays through the crosshair's actual target
 * instead. Applied only when Create is installed (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.foundation.utility.RaycastHelper", remap = false)
public abstract class CreateRaycastMixin {
    @Inject(method = "getTraceTarget", at = @At("HEAD"), cancellable = true)
    private static void unlockedcamera$aimThroughCrosshair(Player player, double range, Vec3 origin, CallbackInfoReturnable<Vec3> cir) {
        Vec3 overridden = UnlockedCameraClient.createTraceTarget(player, range, origin);
        if (overridden != null) {
            cir.setReturnValue(overridden);
        }
    }
}
