package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create's BigOutlines runs after vanilla picking and, for blocks whose outline
 * spans more than one block (steering wheels, tracks), re-raycasts from the
 * player's EYE and overwrites Minecraft#hitResult with what it finds. With the
 * shoulder offset that eye ray is not the crosshair ray, so it replaces a correct
 * hit with whatever big-outline block sits along the player's body direction —
 * e.g. grabbing the steering wheel while the crosshair is on the lever beside it.
 *
 * <p>Re-base it on the crosshair ray at the PLAYER'S depth (not the camera
 * itself): BigOutlines traces the whole segment with no gap filter, so a
 * camera origin let it claim big-outline blocks BEHIND the character — e.g.
 * an ascending track at your back, whose empty collision shape never pulls
 * the camera forward. From the depth point the raw reach is first-person
 * reach, so no setback extension is needed either.
 *
 * <p>Applied only when Create is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Create update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.foundation.block.BigOutlines", remap = false)
public abstract class CreateBigOutlinesMixin {
    @WrapOperation(
            method = "pick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$bigOutlineOrigin(LocalPlayer player, float partialTick, Operation<Vec3> original) {
        // Gated on the same availability the direction hook has (see
        // crosshairHitUsable — RaycastHelper's getTraceTarget recovers Sable
        // plot-space hits onto the camera ray), so ALL of BigOutlines'
        // redirected inputs engage and stand down together. Redirecting only
        // one of them pairs a crosshair origin with a body direction — a
        // hybrid ray corresponding to no gaze.
        Vec3 overridden = UnlockedCameraClient.crosshairHitUsable()
                ? UnlockedCameraClient.crosshairRayGapFreeOrigin(player)
                : null;
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    @WrapOperation(
            method = "pick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"),
            require = 0)
    private static double unlockedcamera$plotAwareRange(Vec3 location, Vec3 reference, Operation<Double> original) {
        double raw = original.call(location, reference);
        // BigOutlines caps its claims at the crosshair hit's distance —
        // astronomical for a Sable plot-space location, which disarmed the cap
        // (through-hull claims, and clicks near a steering wheel hijacked by
        // its big shape). Recover the true distance for exactly the hit's own
        // location object; the candidate-distance calls (world-space, near)
        // pass through on the raw value.
        if (raw <= Mth.square(256.0f)) {
            return raw;
        }
        Double recovered = UnlockedCameraClient.plotAwareHitDistSqr(location, reference);
        return recovered != null ? recovered : raw;
    }
}
