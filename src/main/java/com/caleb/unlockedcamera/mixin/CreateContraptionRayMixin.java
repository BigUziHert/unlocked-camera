package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Contraptions are entities with their own block-space, so Create raycasts them
 * itself from the player's eyes along the body rotation — which the shoulder
 * offset decouples from the crosshair. While the offset is engaged, substitute
 * the crosshair ray, starting at the PLAYER'S depth so the segment spans only
 * what lies in front of the character — a full camera-to-target segment let a
 * right-click reach contraption blocks behind the player (e.g. a stationary
 * elevator wall at your back). Elevator floor selection and contraption
 * controls then match the crosshair exactly.
 * Applied only when Create is installed (see UnlockedCameraMixinPlugin).
 *
 * <p>Enhancement-only, so require = 0: if a Create update moves these call
 * sites, the game launches with
 * the compat silently un-applied instead of crashing — no warning is
 * possible: a bytecode check cannot see MixinExtras' late call-site
 * rewiring (see UnlockedCameraMixinPlugin).
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionHandlerClient", remap = false)
public abstract class CreateContraptionRayMixin {
    @WrapOperation(
            method = "getRayInputs",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition()Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$rayOrigin(LocalPlayer player, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        return overridden != null ? overridden : original.call(player);
    }

    @WrapOperation(
            method = "getRayInputs",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/utility/RaycastHelper;getTraceTarget(Lnet/minecraft/world/entity/player/Player;DLnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 unlockedcamera$rayTarget(Player player, double range, Vec3 origin, Operation<Vec3> original) {
        Vec3 overridden = UnlockedCameraClient.contraptionRayTarget(player, range, origin);
        return overridden != null ? overridden : original.call(player, range, origin);
    }
}
