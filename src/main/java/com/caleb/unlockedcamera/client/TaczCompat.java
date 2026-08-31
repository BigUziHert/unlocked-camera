package com.caleb.unlockedcamera.client;

import net.minecraft.world.item.ItemStack;

/**
 * Reflection bridge to TACZ (Timeless and Classics Zero), so its guns can join
 * the aim hold without a compile-time dependency.
 *
 * <p>TACZ guns need the hold for the same reason a bow release does: their
 * shoot packet (ClientMessagePlayerShoot) carries no rotation at all, and the
 * server fires the bullet along the rotation it last heard — its handler builds
 * the pitch/yaw suppliers as {@code serverPlayer::getXRot}/{@code ::getYRot}.
 * Holding the crosshair aim on the wire therefore lands gun shots exactly like
 * it lands arrows. Guns are not a vanilla ranged type (UseAnim.NONE, not
 * ProjectileItem), so they must be recognized here.
 */
final class TaczCompat {
    private static boolean initialized = false;
    private static Class<?> gunInterface;

    private TaczCompat() {
    }

    /** True if the stack is a TACZ gun (every gun item implements IGun). */
    static boolean isGun(ItemStack stack) {
        if (!initialized) {
            init();
        }
        return gunInterface != null && gunInterface.isInstance(stack.getItem());
    }

    /** True if TACZ is present at all — the crosshair compat asks before forcing a reticle. */
    static boolean isInstalled() {
        if (!initialized) {
            init();
        }
        return gunInterface != null;
    }

    private static void init() {
        initialized = true;
        try {
            gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
        } catch (Throwable t) {
            // TACZ isn't installed (or its internals changed); run standalone.
            gunInterface = null;
        }
    }
}
