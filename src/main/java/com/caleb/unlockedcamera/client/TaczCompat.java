package com.caleb.unlockedcamera.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
 *
 * <p>Guns also never start a vanilla item use (nothing in TACZ calls
 * {@code startUsingItem}): aiming, charging and firing all live in TACZ's own
 * client gun operator, so the mounted-rider gate — which narrows the hold to
 * the actual draw so a steerable mount is not twisted toward the crosshair —
 * asks that operator instead of {@code isUsingItem}. Verified against
 * tacz-neoforge-1.21.1-1.1.8-hotfix-r6: {@code IClientPlayerGunOperator}
 * (isAim / isCharging / getClientShootCoolDown, the latter the milliseconds
 * left before the next shot, 0 when idle) and {@code ShootKey.SHOOT_KEY}.
 */
final class TaczCompat {
    private static boolean initialized = false;
    private static Class<?> gunInterface;
    private static Field shootKeyField;
    private static Method fromLocalPlayer;
    private static Method isAim;
    private static Method isCharging;
    private static Method getClientShootCoolDown;

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

    /**
     * The gun in the player's main hand is being "drawn" in TACZ's sense: the
     * shoot key is down, the gun is aimed down sights, a charge is building,
     * or a shot just went off (burst/auto follow-ups are still coming). False
     * with no gun in the main hand, without TACZ, or if its operator API
     * cannot be reached. Cheap: a cast and a few field reads.
     *
     * <p>Main hand only, deliberately: {@code holdingRangedItem} accepts a gun
     * in either hand (a mere hold is harmless on foot), but TACZ only ever
     * operates the main-hand gun, so an offhand gun cannot be "drawn" and a
     * mounted rider with one gets no hold. Note ADS is a toggle in TACZ's
     * toggle-aim option: the mount follows the crosshair for as long as it
     * stays toggled, consistent with "aiming down sights is the draw".
     */
    static boolean isGunEngaged(LocalPlayer player) {
        if (!initialized) {
            init();
        }
        if (gunInterface == null || fromLocalPlayer == null
                || !gunInterface.isInstance(player.getMainHandItem().getItem())) {
            return false;
        }
        try {
            Object shootKey = shootKeyField.get(null);
            if (shootKey instanceof KeyMapping key && key.isDown()) {
                return true;
            }
            Object operator = fromLocalPlayer.invoke(null, player);
            if (operator == null) {
                return false;
            }
            return Boolean.TRUE.equals(isAim.invoke(operator))
                    || Boolean.TRUE.equals(isCharging.invoke(operator))
                    || ((Long) getClientShootCoolDown.invoke(operator)) > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void init() {
        initialized = true;
        try {
            gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
        } catch (Throwable t) {
            // TACZ isn't installed (or its internals changed); run standalone.
            gunInterface = null;
            return;
        }
        try {
            shootKeyField = Class.forName("com.tacz.guns.client.input.ShootKey").getField("SHOOT_KEY");
            Class<?> operator = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
            fromLocalPlayer = operator.getMethod("fromLocalPlayer", LocalPlayer.class);
            isAim = operator.getMethod("isAim");
            isCharging = operator.getMethod("isCharging");
            getClientShootCoolDown = operator.getMethod("getClientShootCoolDown");
        } catch (Throwable t) {
            // The gun check above still works; only the mounted engagement
            // read degrades (mounted guns fall back to no hold, as before).
            shootKeyField = null;
            fromLocalPlayer = null;
            isAim = null;
            isCharging = null;
            getClientShootCoolDown = null;
        }
    }
}
