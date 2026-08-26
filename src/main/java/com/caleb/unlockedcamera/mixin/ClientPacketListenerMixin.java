package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The server fires projectiles along the rotation it has for the player, so the
 * shot is corrected on the wire rather than by rotating the player — the body
 * is a tug-of-war with Better Third Person, and the server-side HEAD rotation
 * (which crossbows fire along) additionally lags one tick behind any rotation
 * packet, so a click-time sync is always too late.
 *
 * <p>While a ranged or thrown item is held, every outgoing rotation is replaced
 * with the crosshair aim (the tick handler also sends one whenever the aim
 * moves), keeping the server's body and head glued to the crosshair before any
 * click. ServerboundUseItemPacket carries a rotation of its own; it gets the
 * aim too.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketListenerMixin {
    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> unlockedcamera$holdAimRotation(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket.Rot rot) {
            float[] aim = UnlockedCameraClient.continuousAimAngles();
            if (aim != null) {
                return new ServerboundMovePlayerPacket.Rot(aim[0], aim[1], rot.isOnGround());
            }
        } else if (packet instanceof ServerboundMovePlayerPacket.PosRot posRot) {
            float[] aim = UnlockedCameraClient.continuousAimAngles();
            if (aim != null) {
                return new ServerboundMovePlayerPacket.PosRot(
                        posRot.getX(0.0), posRot.getY(0.0), posRot.getZ(0.0),
                        aim[0], aim[1], posRot.isOnGround());
            }
        }
        return packet;
    }

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
