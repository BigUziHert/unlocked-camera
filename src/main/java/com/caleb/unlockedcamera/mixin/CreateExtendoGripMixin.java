package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create's Extendo Grip re-sweeps entities on every click (its
 * InteractionKeyMappingTriggered handler) from the player's EYE along the BODY
 * look and stamps Minecraft#hitResult / crosshairPickEntity right before the
 * click is resolved — so with the shoulder camera (or freelook) the attack
 * lands on the mob the body faces, not the one under the crosshair. Re-base
 * the sweep on the crosshair ray, starting at the player's own depth so a mob
 * behind the character can never be claimed (mirroring cameraRayPick's entity
 * sweep); from there the raw reach is first-person reach, so the range read
 * stays untouched.
 *
 * <p>Applied only when Create is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Create update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.content.equipment.extendoGrip.ExtendoGripItem", remap = false)
public abstract class CreateExtendoGripMixin {
    @WrapOperation(
            method = "dontMissEntitiesWhenYouHaveHighReachDistance",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$sweepOrigin(LocalPlayer player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    @WrapOperation(
            method = "dontMissEntitiesWhenYouHaveHighReachDistance",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$sweepDirection(LocalPlayer player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayDirection(player);
        return overridden != null ? overridden : original.call(player, partialTick);
    }
}
