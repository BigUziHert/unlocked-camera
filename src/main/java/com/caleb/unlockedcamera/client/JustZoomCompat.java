package com.caleb.unlockedcamera.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional, lazily resolved input and lens compatibility with Just Zoom. */
public final class JustZoomCompat {
    private static boolean initialized;
    private static Method isZooming;
    private static boolean fovInitialized;
    private static Method shouldZoomInOutSmooth;
    private static Method getFovModifier;
    private static Field cachedNormalFov;
    private static Field cachedModifiedFov;
    private static long fovModifierReads;

    private JustZoomCompat() {}

    static boolean isZooming() {
        if (!initialized) {
            initialized = true;
            try {
                isZooming = Class.forName("de.keksuccino.justzoom.ZoomHandler").getMethod("isZooming");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Just Zoom is optional; a missing bridge stays disabled.
            }
        }
        if (isZooming != null) {
            try {
                return Boolean.TRUE.equals(isZooming.invoke(null));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                isZooming = null;
            }
        }
        return false;
    }

    /** All calls are on the render thread, including the observer in ZoomHandler. */
    public static long fovModifierReads() {
        return fovModifierReads;
    }

    public static void recordFovModifierRead() {
        fovModifierReads++;
    }

    public static boolean isNonSmoothZooming() {
        if (!isZooming()) {
            return false;
        }
        if (!fovInitialized) {
            fovInitialized = true;
            try {
                Class<?> handler = isZooming.getDeclaringClass();
                shouldZoomInOutSmooth = handler.getMethod("shouldZoomInOutSmooth");
                getFovModifier = handler.getMethod("getFovModifier");
                cachedNormalFov = handler.getField("cachedNormalFov");
                cachedModifiedFov = handler.getField("cachedModifiedFov");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                shouldZoomInOutSmooth = null;
            }
        }
        if (shouldZoomInOutSmooth != null) {
            try {
                return Boolean.FALSE.equals(shouldZoomInOutSmooth.invoke(null));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                shouldZoomInOutSmooth = null;
            }
        }
        return false;
    }

    /**
     * Simply Swords 1.70.2 cancels at RETURN even when its FOV is unchanged,
     * skipping Just Zoom's later RETURN injector. If that injector did run, its
     * getFovModifier call was observed and this leaves the result untouched.
     * Otherwise magnify the final FOV, including other mods' adjustments, once.
     * Smooth zoom already runs through tickFov and never enters this path.
     */
    public static double finishNonSmoothFov(double fov, long readsBefore) {
        if (fovModifierReads != readsBefore || shouldZoomInOutSmooth == null) {
            return fov;
        }
        try {
            double normal = Math.clamp(fov, 1.0, 170.0);
            double modified = Math.clamp(normal * (float) getFovModifier.invoke(null), 1.0, 170.0);
            cachedNormalFov.setDouble(null, normal);
            cachedModifiedFov.setDouble(null, modified);
            return modified;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            shouldZoomInOutSmooth = null;
            return fov;
        }
    }
}
