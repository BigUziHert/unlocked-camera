package com.caleb.contraptioncamera.mixin;

import com.caleb.contraptioncamera.client.ContraptionCameraClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    /**
     * While freelooking, route the mouse deltas to the freelook camera instead of
     * turning the player. The caller (handleAccumulatedMovement) zeroes the
     * accumulated deltas right after, so cancelling here is safe.
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void contraptioncamera$freelook(double movementTime, CallbackInfo ci) {
        if (ContraptionCameraClient.freelookMouseTurn(this.accumulatedDX, this.accumulatedDY)) {
            ci.cancel();
        }
    }
}
