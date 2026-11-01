package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Local module attachment/removal must select the same socket as its preview. */
@Mixin(targets = "com.mred231.aeroworks.content.controls.console.ConsoleBlock", remap = false)
public abstract class AeroworksConsoleRayMixin {
    @ModifyArgs(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lcom/mred231/aeroworks/content/controls/console/ConsoleBlockEntity;nearestFreeMount(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lcom/mred231/aeroworks/content/controls/module/ModuleType;)Lcom/mred231/aeroworks/content/controls/console/ConsoleBlockEntity$MountTarget;"), require = 0)
    private void unlockedcamera$attachRay(Args args, @Local(argsOnly = true) Player player) {
        unlockedcamera$redirect(args, player);
    }

    @ModifyArgs(method = "onWrenched", at = @At(value = "INVOKE",
            target = "Lcom/mred231/aeroworks/content/controls/console/ConsoleBlockEntity;nearestOccupiedMount(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lcom/mred231/aeroworks/content/controls/console/ConsoleBlockEntity$MountTarget;"), require = 0)
    private void unlockedcamera$removeRay(Args args, @Local(argsOnly = true) UseOnContext context) {
        unlockedcamera$redirect(args, context.getPlayer());
    }

    private static void unlockedcamera$redirect(Args args, Player player) {
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        if (origin == null) return;
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        args.set(0, origin);
        args.set(1, origin.add(direction.scale(player.blockInteractionRange())));
    }
}
