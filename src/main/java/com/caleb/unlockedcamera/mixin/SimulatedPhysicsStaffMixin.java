package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The physics staff picks its target with Entity#pick, which raycasts from the
 * player's eye along their body look — not the crosshair, once the shoulder
 * offset moves the camera aside. Point it down the crosshair ray instead.
 *
 * <p>Applied only when Simulated is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Simulated update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class SimulatedPhysicsStaffMixin {
    @WrapOperation(
            method = "onItemUsed",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"),
            require = 0)
    private HitResult unlockedcamera$staffPick(LocalPlayer player, double hitDistance, float partialTick, boolean hitFluids, Operation<HitResult> original) {
        HitResult overridden = UnlockedCameraClient.crosshairPick(player, hitDistance, partialTick, hitFluids);
        return overridden != null ? overridden : original.call(player, hitDistance, partialTick, hitFluids);
    }

    /**
     * Dragging sends the server a target of "eye + look * distance", which tracks
     * the player's body rather than the crosshair. Replace that offset with one
     * that lands on the crosshair ray at the same distance from the eye.
     */
    @WrapOperation(
            method = "sendDraggingData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 unlockedcamera$staffDragTarget(Vec3 lookAngle, double distance, Operation<Vec3> original) {
        // Identity guard: with no ordinal or slice this wraps EVERY Vec3.scale(D)
        // in the method, and require = 0 would hide the mismatch if a Simulated
        // update added another. Only substitute when the receiver really is the
        // player's unit look vector (today's single call site).
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || Math.abs(lookAngle.lengthSqr() - 1.0) > 1.0E-3
                || lookAngle.dot(mc.player.getLookAngle()) < 0.999) {
            return original.call(lookAngle, distance);
        }
        Vec3 overridden = UnlockedCameraClient.crosshairOffsetFromEye(distance);
        return overridden != null ? overridden : original.call(lookAngle, distance);
    }
}
