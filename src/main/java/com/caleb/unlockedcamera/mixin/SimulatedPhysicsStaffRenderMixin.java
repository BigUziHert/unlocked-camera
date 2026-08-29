package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The physics staff's hover highlight is computed separately from its actual use,
 * in PhysicsStaffRenderHandler#updateHoverPos, and likewise raycasts from the
 * player's eye along their body look. Point it down the crosshair ray so the
 * selection box lands where you are aiming.
 *
 * <p>Applied only when Simulated is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Simulated update moves this call
 * site, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffRenderHandler", remap = false)
public abstract class SimulatedPhysicsStaffRenderMixin {
    @WrapOperation(
            method = "updateHoverPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"),
            require = 0)
    private static HitResult unlockedcamera$staffHoverPick(LocalPlayer player, double hitDistance, float partialTick, boolean hitFluids, Operation<HitResult> original) {
        HitResult overridden = UnlockedCameraClient.crosshairPick(player, hitDistance, partialTick, hitFluids);
        return overridden != null ? overridden : original.call(player, hitDistance, partialTick, hitFluids);
    }
}
