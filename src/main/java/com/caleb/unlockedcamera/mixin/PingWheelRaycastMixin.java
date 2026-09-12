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
 * Origin half of the Ping Wheel compat (direction: {@link PingWheelPingMixin}).
 * Its traces start at the camera entity's eye; re-base them on the crosshair
 * ray at the PLAYER'S depth, so the ping's raw range stays first-person
 * reach in front of the character and nothing in the camera-player gap
 * behind them can be pinged. The entity sweep's search box is expanded along
 * a separate view-vector read — redirected too, or the box would trail the
 * body direction while the ray follows the crosshair and miss entities at
 * range.
 *
 * <p>Applied only when Ping Wheel is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Ping Wheel update moves these
 * call sites, the game launches with the compat silently un-applied instead
 * of crashing — no warning is possible: a bytecode check cannot see
 * MixinExtras' late call-site rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "nx.pingwheel.common.math.Raycast", remap = false)
public abstract class PingWheelRaycastMixin {
    @WrapOperation(
            method = {"traceDirectional", "traceDistantAsync"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$traceOrigin(Entity entity, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = entity instanceof Player player ? UnlockedCameraClient.crosshairRayGapFreeOrigin(player) : null;
        return overridden != null ? overridden : original.call(entity, partialTick);
    }

    @WrapOperation(
            method = "traceDirectional",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$searchBoxDirection(Entity entity, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = entity instanceof Player player ? UnlockedCameraClient.crosshairRayDirection(player) : null;
        return overridden != null ? overridden : original.call(entity, partialTick);
    }
}
