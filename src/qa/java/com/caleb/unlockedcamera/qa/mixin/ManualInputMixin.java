package com.caleb.unlockedcamera.qa.mixin;

import com.caleb.unlockedcamera.qa.ManualOffsetRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Minecraft.class, priority = 500)
public abstract class ManualInputMixin {
    @WrapMethod(method = "startUseItem")
    private void manualqa$input(Operation<Void> original) {
        long id = ManualOffsetRecorder.beginChain(); Throwable failure = null;
        try { original.call(); } catch (Throwable e) { failure = e; throw e; }
        finally { ManualOffsetRecorder.endChain(id, failure); }
    }
}
