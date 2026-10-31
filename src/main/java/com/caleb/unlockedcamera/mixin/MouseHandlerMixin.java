package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /**
     * Redirect only the finished turn, after vanilla and other mods have applied
     * sensitivity, zoom, cinematic smoothing, scoping, and inverted Y. Just Zoom
     * modifies the option reads inside turnPlayer, so cancelling at HEAD and
     * reproducing vanilla's calculation bypasses both its sensitivity and its
     * smoothing. Keeping this calculation also advances the same smoothers across
     * freelook transitions and posts NeoForge's turn event exactly once.
     *
     * <p>Better Third Person's HEAD/TAIL bookkeeping can run normally: its custom
     * camera is inactive in first person, the only perspective with freelook.
     */
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void unlockedcamera$freelook(LocalPlayer player, double dx, double dy,
                                         Operation<Void> original) {
        if (!UnlockedCameraClient.freelookMouseTurn(dx, dy)) {
            original.call(player, dx, dy);
        }
    }
}
