package com.caleb.unlockedcamera.qa.mixin;

import com.caleb.unlockedcamera.qa.ManualOffsetRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = MultiPlayerGameMode.class, priority = 500)
public abstract class ManualGameModeMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult manualqa$block(LocalPlayer player, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        long id = ManualOffsetRecorder.beginUse("useItemOn", player, hand, hit); InteractionResult result = null; Throwable failure = null;
        try { return result = original.call(player, hand, hit); } catch (Throwable e) { failure = e; throw e; }
        finally { ManualOffsetRecorder.endUse(id, player, hit, result, failure); }
    }
    @WrapMethod(method = "useItem")
    private InteractionResult manualqa$item(Player player, InteractionHand hand, Operation<InteractionResult> original) {
        long id = ManualOffsetRecorder.beginUse("useItem", player, hand, null); InteractionResult result = null; Throwable failure = null;
        try { return result = original.call(player, hand); } catch (Throwable e) { failure = e; throw e; }
        finally { ManualOffsetRecorder.endUse(id, player, null, result, failure); }
    }
}
