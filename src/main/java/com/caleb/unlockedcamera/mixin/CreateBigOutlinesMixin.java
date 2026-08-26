package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
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
 * <p>Re-base it on the camera so it only claims blocks the crosshair is actually
 * over, extending its range by the camera setback to preserve reach.
 *
 * <p>Applied only when Create is installed (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.foundation.block.BigOutlines", remap = false)
public abstract class CreateBigOutlinesMixin {
    @WrapOperation(
            method = "pick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 unlockedcamera$bigOutlineOrigin(LocalPlayer player, float partialTick, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayOrigin(player);
        return overridden != null ? overridden : original.call(player, partialTick);
    }

    @WrapOperation(
            method = "pick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
    private static double unlockedcamera$bigOutlineRange(LocalPlayer player, Holder<?> attribute, Operation<Double> original) {
        return original.call(player, attribute) + UnlockedCameraClient.crosshairRaySetback(player);
    }
}
