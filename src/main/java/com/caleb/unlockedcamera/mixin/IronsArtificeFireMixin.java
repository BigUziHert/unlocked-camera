package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.IronsArtificeAim;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "io.redspace.irons_artifice.client.InputHandler", remap = false)
public abstract class IronsArtificeFireMixin {
    // Both semi-automatic and automatic shots reach this constructor after the
    // local fire check succeeds. Dry fires and cooldown ticks need no aim ray.
    @ModifyArg(method = "tryFire", at = @At(value = "INVOKE",
            target = "Lio/redspace/irons_artifice/network/packets/ServerboundFireGunPacket;<init>(Lnet/minecraft/world/phys/Vec3;)V"),
            index = 0, require = 0)
    private static Vec3 unlockedcamera$aimFirePacket(Vec3 direction) {
        return IronsArtificeAim.fireDirection(direction);
    }
}
