package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.BucketUseRayContext;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Match bucket prediction to the already-corrected server use packet's aim. */
@Mixin(Item.class)
public abstract class BucketPovRayMixin {
    @Inject(method = "getPlayerPOVHitResult", at = @At("HEAD"), cancellable = true)
    private static void unlockedcamera$bucketPov(Level level, Player player, ClipContext.Fluid fluidMode,
                                                CallbackInfoReturnable<BlockHitResult> result) {
        if (!BucketUseRayContext.matches(level, player)) return;
        // Keep the bucket's exact fluid policy: SOURCE_ONLY for collection,
        // NONE for ordinary placement, or a custom subclass's requested mode.
        BlockHitResult hit = UnlockedCameraClient.crosshairBlockPick(player, player.blockInteractionRange(), fluidMode);
        if (hit != null) {
            BucketUseRayContext.capturePacketAim(level, player, hit);
            result.setReturnValue(hit);
        }
    }
}
