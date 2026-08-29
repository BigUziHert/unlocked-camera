package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
 *
 * <p>Enhancement-only, so require = 0: if a Simulated update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueClientHandler", remap = false)
public abstract class SimulatedHoneyGlueMixin {
    @WrapOperation(
            method = {"getHitResult", "updateHovered"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getEyePosition()Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 unlockedcamera$glueRayOrigin(Player player, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayOrigin(player);
        return overridden != null ? overridden : original.call(player);
    }

    @WrapOperation(
            method = "getHitResult",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 unlockedcamera$glueRayDirection(Player player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayDirection(player);
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    /**
     * Both rays measure their reach from the interaction-range attribute, but
     * the wrapped origin sits at the CAMERA — a shoulder-width and a zoom
     * behind the eye. Without extending the reach by that setback the segment
     * ends at the player and every clip misses, which killed the hover
     * outline in third person. Same pattern as the steering wheel's range wrap.
     */
    @WrapOperation(
            method = {"getHitResult", "updateHovered"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAttributeValue(Lnet/minecraft/core/Holder;)D"),
            require = 0)
    private double unlockedcamera$glueRayRange(Player player, Holder<?> attribute, Operation<Double> original) {
        double value = original.call(player, attribute);
        // Only reach attributes get the setback: getAttributeValue is a common
        // enough call that a Simulated update could add another attribute read
        // to these methods, and require = 0 would never flag the mismatch.
        if (attribute != Attributes.BLOCK_INTERACTION_RANGE && attribute != Attributes.ENTITY_INTERACTION_RANGE) {
            return value;
        }
        return value + UnlockedCameraClient.crosshairRaySetback(player);
    }
}
