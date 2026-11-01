package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.caleb.unlockedcamera.client.AddonInteractionAim;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/** AeroWorks re-picks protruding modules after vanilla's renderer pick. */
@Mixin(targets = "com.mred231.aeroworks.content.controls.console.ConsoleClientRaycast", remap = false)
public abstract class AeroworksConsolePickMixin {
    @WrapMethod(method = "raycast", require = 0)
    private static Double unlockedcamera$consolePick(Vec3 eye, Vec3 bodyDirection, @Coerce Object console,
                                                     float partialTick, Operation<Double> original) {
        var player = Minecraft.getInstance().player;
        Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(player);
        if (origin == null) return original.call(eye, bodyDirection, console, partialTick);
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        Double distance = original.call(origin, direction, console, partialTick);
        if (distance == null) return null;
        Double parameter = AddonInteractionAim.consoleRayParameter((BlockEntity) console, direction, partialTick, distance);
        if (parameter == null) return null;
        // The renderer compares this distance with its existing hit measured
        // from the original, Sable-interpolated eye. Keep that same metric;
        // the ray itself is gap-free and the addon's reach is unchanged.
        Vec3 target = origin.add(direction.scale(parameter));
        double eyeDistance = target.distanceToSqr(eye);
        return eyeDistance <= player.blockInteractionRange() * player.blockInteractionRange() ? eyeDistance : null;
    }
}
