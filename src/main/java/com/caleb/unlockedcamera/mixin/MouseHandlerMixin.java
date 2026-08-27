package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Final;
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
    @Shadow
    @Final
    private SmoothDouble smoothTurnX;
    @Shadow
    @Final
    private SmoothDouble smoothTurnY;

    /**
     * While freelooking, route the mouse deltas to the freelook camera instead of
     * turning the player. The caller (handleAccumulatedMovement) zeroes the
     * accumulated deltas right after, so cancelling here is safe. Vanilla's
     * cinematic-camera smoothers are reset too — freelook runs its own, and a
     * pan mid-smoothing when freelook engages would otherwise sit frozen in
     * vanilla's and flush its remainder into the first turn after release.
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void unlockedcamera$freelook(double movementTime, CallbackInfo ci) {
        if (UnlockedCameraClient.freelookMouseTurn(this.accumulatedDX, this.accumulatedDY, movementTime)) {
            this.smoothTurnX.reset();
            this.smoothTurnY.reset();
            ci.cancel();
        }
    }
}
