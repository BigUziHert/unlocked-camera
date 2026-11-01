package com.caleb.unlockedcamera.qa.mixin;

import com.caleb.unlockedcamera.qa.ManualOffsetRecorder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ManualConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
    private void manualqa$packet(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        ManualOffsetRecorder.outgoing((Connection) (Object) this, packet);
    }
}
