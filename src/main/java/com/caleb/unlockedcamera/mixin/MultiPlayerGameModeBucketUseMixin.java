package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.BucketUseRayContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps the captured bucket ray alive only through its normal packet send. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeBucketUseMixin {
    @WrapMethod(method = "useItem")
    private InteractionResult unlockedcamera$bucketRequest(Player player, InteractionHand hand,
                                                          Operation<InteractionResult> original) {
        // Vanilla constructs the packet before predicting ItemStack.use, then
        // sends it afterward. The captured source hit survives client removal
        // of water, but cannot leak out to a subsequent click or nested request.
        return BucketUseRayContext.duringRequest(player.level(), player, hand, () -> original.call(player, hand));
    }
}
