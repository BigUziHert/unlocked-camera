package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.BucketUseRayContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Includes custom BucketItem.use overrides without affecting other held items. */
@Mixin(ItemStack.class)
public abstract class ItemStackBucketUseMixin {
    @WrapMethod(method = "use")
    private InteractionResultHolder<ItemStack> unlockedcamera$bucketUseScope(
            Level level, Player player, InteractionHand hand,
            Operation<InteractionResultHolder<ItemStack>> original) {
        ItemStack stack = (ItemStack) (Object) this;
        if (!level.isClientSide) {
            return original.call(level, player, hand);
        }
        if (!(stack.getItem() instanceof BucketItem)) {
            if (!BucketUseRayContext.matches(level, player)) return original.call(level, player, hand);
            return BucketUseRayContext.outsideUse(() -> original.call(level, player, hand));
        }
        return BucketUseRayContext.duringUse(level, player, hand, () -> original.call(level, player, hand));
    }
}
