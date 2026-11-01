package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Powergrid independently re-picks before accepting either wire or cord use. */
@Mixin(targets = {"org.patryk3211.powergrid.electricity.wire.WireItem",
        "org.patryk3211.powergrid.electricity.wire.powercord.CordItem"}, remap = false)
public abstract class PowergridWireRayMixin {
    @WrapOperation(method = "useOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"), require = 0)
    private static HitResult unlockedcamera$wirePick(Player player, double range, float partialTick,
                                                     boolean fluids, Operation<HitResult> original) {
        HitResult hit = UnlockedCameraClient.crosshairPick(player, range, partialTick, fluids);
        return hit != null ? hit : original.call(player, range, partialTick, fluids);
    }
}
