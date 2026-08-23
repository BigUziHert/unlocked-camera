package com.caleb.unlockedcamera.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@code Camera#move} for the shoulder offset. */
@Mixin(Camera.class)
public interface CameraInvoker {
    /** dx moves along the camera-local +X axis, which is camera-RIGHT (left is -X). */
    @Invoker("move")
    void unlockedcamera$move(float zoom, float dy, float dx);
}
