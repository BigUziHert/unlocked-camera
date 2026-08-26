package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Honey glue finds its target and its hover highlight by raycasting from the
 * player's eye along their body look, which the shoulder offset decouples from
 * the crosshair. Point both down the crosshair ray instead.
 *
 * <p>Applied only when Simulated is installed (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueClientHandler", remap = false)
public abstract class SimulatedHoneyGlueMixin {
    @WrapOperation(
            method = {"getHitResult", "updateHovered"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getEyePosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 unlockedcamera$glueRayOrigin(Player player, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayOrigin(player);
        return overridden != null ? overridden : original.call(player);
    }

    @WrapOperation(
            method = "getHitResult",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 unlockedcamera$glueRayDirection(Player player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayDirection(player);
        return overridden != null ? overridden : original.call(player, partialTick);
    }
}
