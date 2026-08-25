package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server fires projectiles along the rotation it has for the player, so the
 * shot is corrected here — at the last point before transmission — rather than
 * by rotating the player beforehand. Rotating the body is a tug-of-war with
 * Better Third Person, which aims the player on interact and wins often enough
 * to make the body visibly flip back and forth.
 *
 * <p>Two routes carry a shot:
 * <ul>
 *   <li>ServerboundUseItemPacket carries a rotation of its own (crossbows, ender
 *       pearls, snowballs, potions) — rewrite it.</li>
 *   <li>Releasing a drawn bow or trident sends ServerboundPlayerActionPacket,
 *       which has no rotation, so the server uses whatever it last heard. Send a
 *       rotation update immediately ahead of it.</li>
 * </ul>
 *
 * <p>The server can also process a shot a few ticks late, along the last
 * rotation it heard; a fresh body rotation arriving in that window would aim
 * the deferred shot at the body. So after every corrected shot, outgoing
 * rotations are pinned to the aim briefly.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketListenerMixin {
    /** Guards the rotation packet we send from re-entering this handler. */
    private static boolean unlockedcamera$sendingAimSync;

    private static float[] unlockedcamera$pinnedAim;
    private static long unlockedcamera$pinUntilMillis;

    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> unlockedcamera$pinRotationAfterShot(Packet<?> packet) {
        if (unlockedcamera$pinnedAim == null || System.currentTimeMillis() >= unlockedcamera$pinUntilMillis) {
            return packet;
        }
        if (packet instanceof ServerboundMovePlayerPacket.Rot rot) {
            return new ServerboundMovePlayerPacket.Rot(
                    unlockedcamera$pinnedAim[0], unlockedcamera$pinnedAim[1], rot.isOnGround());
        }
        if (packet instanceof ServerboundMovePlayerPacket.PosRot posRot) {
            return new ServerboundMovePlayerPacket.PosRot(
                    posRot.getX(0.0), posRot.getY(0.0), posRot.getZ(0.0),
                    unlockedcamera$pinnedAim[0], unlockedcamera$pinnedAim[1], posRot.isOnGround());
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
        unlockedcamera$pinnedAim = aim;
        unlockedcamera$pinUntilMillis = System.currentTimeMillis() + 300;
        return new ServerboundUseItemPacket(use.getHand(), use.getSequence(), aim[0], aim[1]);
    }

    @Inject(method = "send", at = @At("HEAD"))
    private void unlockedcamera$aimBeforeRelease(Packet<?> packet, CallbackInfo ci) {
        if (unlockedcamera$sendingAimSync
                || !(packet instanceof ServerboundPlayerActionPacket action)
                || action.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float[] aim = UnlockedCameraClient.crosshairAimAngles();
        if (aim == null || mc.player == null) {
            return;
        }
        unlockedcamera$pinnedAim = aim;
        unlockedcamera$pinUntilMillis = System.currentTimeMillis() + 300;
        unlockedcamera$sendingAimSync = true;
        try {
            ((ClientCommonPacketListenerImpl) (Object) this)
                    .send(new ServerboundMovePlayerPacket.Rot(aim[0], aim[1], mc.player.onGround()));
        } finally {
            unlockedcamera$sendingAimSync = false;
        }
    }
}
