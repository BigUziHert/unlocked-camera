package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The steering wheel gates grabbing on SteeringWheelBlock#lookingAtWheel, which
 * builds a ray from the player's eye along their BODY view vector and clips the
 * wheel's voxel shape against it. The shoulder offset decouples that ray from the
 * crosshair, so the wheel answers "yes" while you point somewhere else (and "no"
 * while you point at it). Re-base the ray on the camera so it matches the
 * crosshair, extending its length by the camera setback to preserve reach.
 *
 * <p>Applied only when Simulated is installed (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock", remap = false)
public abstract class SimulatedSteeringWheelMixin {
    private static final String LOOKING_AT_WHEEL =
            "lookingAtWheel(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;FLnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;)Z";

    @WrapOperation(
            method = LOOKING_AT_WHEEL,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 unlockedcamera$wheelRayOrigin(Player player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayOrigin();
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    @WrapOperation(
            method = LOOKING_AT_WHEEL,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 unlockedcamera$wheelRayDirection(Player player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayDirection();
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    @WrapOperation(
            method = LOOKING_AT_WHEEL,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;blockInteractionRange()D"))
    private static double unlockedcamera$wheelRayRange(Player player, Operation<Double> original) {
        return original.call(player) + UnlockedCameraClient.crosshairRaySetback();
    }
}
