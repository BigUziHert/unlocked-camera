package com.caleb.unlockedcamera.mixin;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Better Combat selects melee targets with its own oriented hitbox, independently
 * of vanilla picking. Its body-angle reads ignored first-person freelook and the
 * shoulder camera's parallax, even when the cursor target was correct.
 *
 * <p>Turn only the hitbox toward the existing crosshair hit. Keeping its origin,
 * dimensions and range leaves Better Combat's reach and obstruction checks in
 * place, and its radial filter automatically uses the same adjusted axis. This
 * adds no clips or entity queries and never rotates the player's body. Better
 * Combat sends the resulting target IDs; its server retains relation/range
 * validation without needing an extra rotation packet.
 */
@Mixin(targets = "net.bettercombat.client.collision.TargetFinder", remap = false)
public abstract class BetterCombatTargetMixin {
    @ModifyArgs(
            method = "findAttackTargetResult",
            at = @At(value = "INVOKE",
                    target = "Lnet/bettercombat/client/collision/OrientedBoundingBox;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;FF)V"),
            require = 0)
    private static void unlockedcamera$aimMeleeHitbox(Args args, @Local(argsOnly = true) Player player) {
        Vec3 direction = UnlockedCameraClient.crosshairRayDirection(player);
        if (direction == null) {
            return;
        }
        Vec3 origin = args.get(0);
        // Reuse the current pick, including Sable's plot-space recovery; running
        // another projectile aim sweep here would multiply sublevel clip work.
        Vec3 target = UnlockedCameraClient.createTraceTarget(player, 1.0, origin);
        if (target != null) {
            direction = target.subtract(origin);
        }
        double horizontal = Math.hypot(direction.x, direction.z);
        if (!Double.isFinite(horizontal) || !Double.isFinite(direction.y)
                || direction.lengthSqr() < 1.0E-8) {
            return;
        }
        args.set(2, (float) -Math.toDegrees(Math.atan2(direction.y, horizontal)));
        args.set(3, (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0));
    }
}
