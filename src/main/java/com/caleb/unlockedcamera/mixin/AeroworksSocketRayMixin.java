package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Placement/removal previews share AeroWorks' private eyeRay helper. */
@Mixin(targets = "com.mred231.aeroworks.content.controls.socket.SocketPlacementClient", remap = false)
public abstract class AeroworksSocketRayMixin {
    @ModifyArgs(method = "eyeRay", at = @At(value = "INVOKE",
            target = "Lcom/mred231/aeroworks/content/controls/socket/SocketPlacementClient$Ray;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"), require = 0)
    private static void unlockedcamera$socketRay(Args args, @Local(argsOnly = true) Player player) {
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        if (origin == null) return;
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        args.set(0, origin);
        args.set(1, origin.add(direction.scale(player.blockInteractionRange())));
    }
}
