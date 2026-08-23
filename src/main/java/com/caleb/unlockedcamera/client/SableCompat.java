package com.caleb.unlockedcamera.client;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection bridge to Sable (Create Aeronautics' physics engine), so we can defer
 * to its contraption camera without a compile-time dependency.
 *
 * <p>Sable's own camera mixins gate on {@code Sable.HELPER.getVehicleSubLevel(entity) != null}
 * to decide the player is seated in a physics contraption ("sublevel"); we ask the
 * exact same question.
 */
final class SableCompat {
    private static boolean initialized = false;
    private static Field helperField;
    private static Method getVehicleSubLevel;
    private static Method getContaining;

    private SableCompat() {
    }

    /** True if the entity is seated in a Sable sublevel (an Aeronautics contraption). */
    static boolean isRidingSubLevel(Entity entity) {
        if (!initialized) {
            init();
        }
        if (helperField == null) {
            return false;
        }
        try {
            Object helper = helperField.get(null);
            return helper != null && getVehicleSubLevel.invoke(helper, entity) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True if the entity is inside a Sable sublevel's space (standing on the deck
     * of an Aeronautics contraption). Sable rewrites the vanilla picking pipeline
     * to trace into sublevels, so our own raycasts can't see those blocks and
     * must defer.
     */
    static boolean isInsideSubLevel(Entity entity) {
        if (!initialized) {
            init();
        }
        if (helperField == null || getContaining == null) {
            return false;
        }
        try {
            Object helper = helperField.get(null);
            return helper != null && getContaining.invoke(helper, entity.level(), entity.position()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void init() {
        initialized = true;
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Class<?> companion = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion");
            Method vehicle = companion.getMethod("getVehicleSubLevel", Entity.class);
            helperField = sable.getField("HELPER");
            getVehicleSubLevel = vehicle;
            try {
                getContaining = companion.getMethod("getContaining",
                        net.minecraft.world.level.Level.class, net.minecraft.core.Position.class);
            } catch (Throwable t) {
                // Sable version without this method; only the riding check works.
                getContaining = null;
            }
        } catch (Throwable t) {
            // Sable isn't installed (or its internals changed); run standalone.
            helperField = null;
            getVehicleSubLevel = null;
            getContaining = null;
        }
    }
}
