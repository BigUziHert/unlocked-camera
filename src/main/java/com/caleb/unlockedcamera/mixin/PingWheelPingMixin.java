package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ping Wheel places a ping by raycasting from the camera entity's EYE along
 * its view vector — the body rotation, which the shoulder offset decouples
 * from the crosshair — so a ping under this camera landed where the head
 * faced, not where the crosshair pointed. This is the direction half: the
 * view vector read that seeds both the near trace and the Distant Horizons
 * long-range fallback becomes the camera forward. The origin half lives in
 * {@link PingWheelRaycastMixin}. Bonus: a ping during first-person freelook
 * goes where you are actually looking.
 *
 * <p>Applied only when Ping Wheel is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Ping Wheel update moves this call
 * site, the game launches with the compat silently un-applied instead of
 * crashing — no warning is possible: a bytecode check cannot see MixinExtras'
 * late call-site rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "nx.pingwheel.common.core.PingController", remap = false)
public abstract class PingWheelPingMixin {
    @WrapOperation(
            method = "performPingAction",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$pingDirection(Entity entity, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = entity instanceof Player player ? UnlockedCameraClient.crosshairRayDirection(player) : null;
        return overridden != null ? overridden : original.call(entity, partialTick);
    }
}
