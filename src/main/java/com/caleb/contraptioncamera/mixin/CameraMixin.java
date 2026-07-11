package com.caleb.contraptioncamera.mixin;

import com.caleb.contraptioncamera.client.ContraptionCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private BlockGetter level;
    @Shadow
    private Entity entity;
    @Shadow
    private Vec3 position;
    @Shadow
    @Final
    private Vector3f forwards;

    /**
     * While the contraption camera is active, replace vanilla's camera collision with
     * our own: leaves don't block the camera, and obstructions release smoothly
     * instead of snapping. Returns null (no override) in all other camera modes.
     */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void contraptioncamera$smoothCollision(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        Float overridden = ContraptionCameraClient.overrideMaxZoom(
                this.level, this.entity, this.position, this.forwards, desiredDistance);
        if (overridden != null) {
            cir.setReturnValue(overridden);
        }
    }
}
