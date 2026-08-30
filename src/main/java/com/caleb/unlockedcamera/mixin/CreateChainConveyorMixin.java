package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create 6's chain-conveyor interaction handler seeds its occlusion tie-break
 * with mc.hitResult's RAW squared eye distance. On a Sable sublevel that
 * location is plot-space — thousands of blocks away — so the seed goes
 * astronomical and the handler's only occlusion check (candidate farther than
 * the crosshair hit → reject) can never fire: chain points became selectable
 * and clickable THROUGH the hull wall the crosshair rested on. Recover the
 * hit's true world-space distance for exactly that read; every other distance
 * in the method is world-space and passes through on the raw value.
 *
 * <p>Applied only when Create is installed (see UnlockedCameraMixinPlugin);
 * the target class exists only in Create 6, and on older jars the mixin simply
 * never applies.
 *
 * <p>Enhancement-only, so require = 0: if a Create update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorInteractionHandler", remap = false)
public abstract class CreateChainConveyorMixin {
    @WrapOperation(
            method = "clientTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"),
            require = 0)
    private static double unlockedcamera$plotAwareDistance(Vec3 location, Vec3 reference, Operation<Double> original) {
        double raw = original.call(location, reference);
        // World-space distances (chain-point candidates, near hits) keep the
        // raw value; only an astronomical receiver that IS the crosshair hit's
        // own location object gets the plot-space recovery.
        if (raw <= Mth.square(256.0f)) {
            return raw;
        }
        Double recovered = UnlockedCameraClient.plotAwareHitDistSqr(location, reference);
        return recovered != null ? recovered : raw;
    }
}
