package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The quill's free-selection branch bypasses RaycastHelper and uses body look.
 * Its block-selection branch starts RaycastHelper at the eye. Rebase both onto
 * the camera ray at the player's depth, keeping each branch's original range.
 * Neither branch derives world-space ray endpoints from a Sable plot-space hit.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.client.SchematicAndQuillHandler", remap = false)
public abstract class CreateSchematicSelectionMixin {
    // Create 6.0.10: the first Vec3.add(Vec3) is eye + look * range in the
    // ACTIVATE_TOOL branch. Replace the whole endpoint so origin and direction
    // cannot be redirected independently into a hybrid ray.
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", ordinal = 0),
            require = 0)
    private Vec3 unlockedcamera$freeSelection(Vec3 eye, Vec3 offset, Operation<Vec3> original) {
        Player player = Minecraft.getInstance().player;
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        return origin != null && direction != null
                ? origin.add(direction.scale(offset.length())) : original.call(eye, offset);
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/utility/RaycastHelper;rayTraceRange(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;D)Lnet/minecraft/world/phys/BlockHitResult;"),
            require = 0)
    private BlockHitResult unlockedcamera$blockSelection(Level level, Player player, double range,
                                                         Operation<BlockHitResult> original) {
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        if (origin == null || direction == null) {
            return original.call(level, player, range);
        }
        // Match RaycastHelper's COLLIDER/NONE policy. Retain Level.clip so
        // Sable can project the ray and return its original plot-space result.
        return level.clip(new ClipContext(origin, origin.add(direction.scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    }
}
