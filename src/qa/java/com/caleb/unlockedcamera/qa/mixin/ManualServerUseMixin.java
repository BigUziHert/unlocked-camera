package com.caleb.unlockedcamera.qa.mixin;

import com.caleb.unlockedcamera.qa.ManualOffsetRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Physical-client QA config only: observes the integrated server without modifying its behavior. */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 500)
public abstract class ManualServerUseMixin {
    @Shadow public ServerPlayer player;
    @WrapMethod(method = "handleUseItemOn")
    private void manualqa$block(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        long id = ManualOffsetRecorder.serverBegin(player, packet.getSequence(), packet.getHand(), packet.getHitResult()); Throwable failure = null;
        try { original.call(packet); } catch (Throwable e) { failure = e; throw e; }
        finally { ManualOffsetRecorder.serverEnd(id, player, failure); }
    }
    @WrapMethod(method = "handleUseItem")
    private void manualqa$item(ServerboundUseItemPacket packet, Operation<Void> original) {
        long id = ManualOffsetRecorder.serverBegin(player, packet.getSequence(), packet.getHand(), null); Throwable failure = null;
        try { original.call(packet); } catch (Throwable e) { failure = e; throw e; }
        finally { ManualOffsetRecorder.serverEnd(id, player, failure); }
    }
}
