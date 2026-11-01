package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Align the worldshaper's independent material-selection ray with the crosshair. */
@Mixin(targets = "com.simibubi.create.content.equipment.zapper.ZapperInteractionHandler", remap = false)
public abstract class CreateZapperSelectionMixin {
    @ModifyArgs(
            method = "trySelect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ClipContext;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V"),
            require = 0)
    private static void unlockedcamera$selectionRay(Args args) {
        if (!(args.get(4) instanceof Player player)) {
            return;
        }
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        if (origin == null || direction == null) {
            return;
        }
        Vec3 oldOrigin = args.get(0), oldTarget = args.get(1);
        // Preserve the raw range chosen by Create's getRange(stack): no camera
        // setback bonus and no block in the camera-player gap. Modify both
        // endpoints together, preserving the original OUTLINE/NONE context and
        // Level.clip's Sable handling. Never re-clip from a plot-space hit.
        double range = oldOrigin.distanceTo(oldTarget);
        args.set(0, origin);
        args.set(1, origin.add(direction.scale(range)));
    }
}
