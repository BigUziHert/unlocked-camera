package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the continuous aim hold is active, the server's stored rotation IS the
 * stamped crosshair aim — so any server-initiated position correction with
 * ABSOLUTE rotation flags (an ender pearl landing mid-hold, "moved wrongly"
 * on collision mismatches, "moved too quickly", the pending-teleport re-send)
 * echoes that aim back, and vanilla stamps it into the player's REAL rotation
 * and previous-tick rotation: the whole view wrenches toward the crosshair
 * with no interpolation — double the deflection under freelook, where the
 * still-applied freelook offset compounds. Vanilla's own corrections are
 * rotation-neutral in practice only because the server normally stores the
 * true rotation; the hold breaks that assumption.
 *
 * <p>Restore the pre-packet rotation after vanilla applies the packet whenever
 * the incoming absolute rotation is recognizably the held aim. The position
 * correction is untouched, the teleport confirm has already gone out (server
 * state is unchanged by the restore), and the next tick's hold re-stamps the
 * server — exactly the drop-and-re-stamp pattern the hold already uses.
 */
@Mixin(ClientPacketListener.class)
public abstract class TeleportRotationMixin {
    @Unique
    private float unlockedcamera$preYRot;
    @Unique
    private float unlockedcamera$preXRot;
    @Unique
    private float unlockedcamera$preYRotO;
    @Unique
    private float unlockedcamera$preXRotO;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void unlockedcamera$captureRotation(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        // Also runs on the netty thread before ensureRunningOnSameThread
        // re-schedules the packet; the main-thread invocation overwrites this
        // capture, so the capture/restore pair that matters is same-thread.
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            unlockedcamera$preYRot = player.getYRot();
            unlockedcamera$preXRot = player.getXRot();
            unlockedcamera$preYRotO = player.yRotO;
            unlockedcamera$preXRotO = player.xRotO;
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void unlockedcamera$restoreRotation(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || packet.getRelativeArguments().contains(RelativeMovement.X_ROT)
                || packet.getRelativeArguments().contains(RelativeMovement.Y_ROT)
                || !UnlockedCameraClient.isHeldAimEcho(packet.getYRot(), packet.getXRot())) {
            return;
        }
        player.setYRot(unlockedcamera$preYRot);
        player.setXRot(unlockedcamera$preXRot);
        player.yRotO = unlockedcamera$preYRotO;
        player.xRotO = unlockedcamera$preXRotO;
    }
}
