package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The server fires projectiles along the rotation carried by ServerboundUseItemPacket
 * (see ServerGamePacketListenerImpl#handleUseItem, which applies it via absRotateTo).
 *
 * <p>Aiming the player before the use runs into an ordering fight — Better Third
 * Person also aims the player on interact, and whichever writes last wins — so the
 * rotation is corrected here instead, on the outgoing packet itself. Nothing runs
 * after this point, so a charged crossbow, an ender pearl, a snowball and a splash
 * potion all fire where the crosshair points regardless of who else moved the body.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketListenerMixin {
    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> unlockedcamera$aimUsePacket(Packet<?> packet) {
        if (!(packet instanceof ServerboundUseItemPacket use)) {
            return packet;
        }
        float[] aim = UnlockedCameraClient.crosshairAimAngles();
        if (aim == null) {
            return packet;
        }
        return new ServerboundUseItemPacket(use.getHand(), use.getSequence(), aim[0], aim[1]);
    }
}
