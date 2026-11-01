package com.caleb.unlockedcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection bridge to Sable (Create Aeronautics' physics engine), so we can defer
 * to its contraption camera without a compile-time dependency.
 *
 * <p>Sable's own camera mixins gate on {@code Sable.HELPER.getVehicleSubLevel(entity) != null}
 * to decide the player is seated in a physics contraption ("sublevel"); we ask the
 * exact same question.
 *
 * <p>A second, independent bridge reaches Sable's clip pose stack
 * ({@code LevelPoseProviderExtension} on the client level): Sable wraps the
 * render-level pick call to clip sublevels at their INTERPOLATED render pose
 * for the frame, and the deferred render pick (see
 * {@link UnlockedCameraClient#shouldDeferRenderPick}) runs outside that
 * wrapper, so it pushes the same supplier itself. Verified against
 * sable-neoforge-1.21.1-2.0.3: {@code sable$pushPoseSupplier} /
 * {@code sable$popPoseSupplier} take a fastutil {@code Function<SubLevel,
 * Pose3dc>}, and Sable's own wrapper supplies
 * {@code ClientSubLevel#renderPose(float)}. If the bridge cannot be resolved
 * while Sable is installed, the pick simply is not deferred.
 */
final class SableCompat {
    private static boolean initialized = false;
    /** Sable's main class loaded — decided on its own, not on whether the
     * seat bridge below resolved, so a renamed helper cannot masquerade as
     * "Sable absent" and let the pose bridge think there is nothing to push. */
    private static boolean sablePresent = false;
    private static Field helperField;
    private static Method getVehicleSubLevel;

    /** Pose bridge state: 0 = not yet probed, 1 = Sable absent (nothing to
     * push), 2 = ready, 3 = Sable present but its pose stack is unreachable. */
    private static int poseBridge = 0;
    private static Class<?> poseProviderExtension;
    private static Method pushPoseSupplier;
    private static Method popPoseSupplier;
    /** {@code ClientSubLevel#renderPose(float)} as a handle: it runs once per
     * sublevel per clip inside the deferred pick (BigOutlines probes many),
     * so skip Method.invoke's boxing and access checks. */
    private static MethodHandle renderPose;
    private static MethodHandle logicalPose;
    private static boolean interactionBridgeInitialized;
    private static Method containingPosition;
    private static Method interactionLogicalPose;
    private static Method transformPosition;
    private static Method interactionRenderPose;
    private static Method inverseNormal;

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

    private static void init() {
        initialized = true;
        Class<?> sable;
        try {
            sable = Class.forName("dev.ryanhcode.sable.Sable");
            sablePresent = true;
        } catch (Throwable t) {
            // Sable isn't installed; run standalone.
            return;
        }
        try {
            Class<?> companion = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion");
            Method vehicle = companion.getMethod("getVehicleSubLevel", Entity.class);
            helperField = sable.getField("HELPER");
            getVehicleSubLevel = vehicle;
        } catch (Throwable t) {
            // Sable's internals changed; the seat check degrades to "never seated".
            helperField = null;
            getVehicleSubLevel = null;
        }
    }

    /** World-space target for a server-validated interaction at the tick pose. */
    static Vec3 interactionWorldPoint(Level level, Vec3 point) {
        if (!initialized) init();
        if (!sablePresent) return point;
        initInteractionBridge();
        if (containingPosition != null) {
            try {
                Object sublevel = containingPosition.invoke(helperField.get(null), level, point);
                return sublevel == null ? point
                        : (Vec3) transformPosition.invoke(interactionLogicalPose.invoke(sublevel), point);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                // Never interpret an unavailable plot-space transform as a
                // world-space aim toward Sable's distant storage coordinates.
            }
        }
        Entity player = Minecraft.getInstance().player;
        return player != null && point.distanceToSqr(player.getEyePosition()) < 256.0 * 256.0 ? point : null;
    }

    private static void initInteractionBridge() {
        if (!interactionBridgeInitialized) {
            interactionBridgeInitialized = true;
            try {
                if (helperField == null) helperField = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER");
                containingPosition = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion")
                        .getMethod("getContaining", Level.class, net.minecraft.core.Position.class);
                interactionLogicalPose = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getMethod("logicalPose");
                transformPosition = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc")
                        .getMethod("transformPosition", Vec3.class);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                containingPosition = null;
            }
        }
    }

    /** Converts AeroWorks' local squared clip distance back to its world-ray parameter. */
    static Double consoleRayParameter(BlockEntity target, Vec3 direction, float partialTick, double localDistanceSquared) {
        if (!initialized) init();
        if (!sablePresent) return rayParameter(null, direction, localDistanceSquared);
        initInteractionBridge();
        if (containingPosition == null || target.getLevel() == null) return null;
        try {
            Object sublevel = containingPosition.invoke(helperField.get(null), target.getLevel(), Vec3.atCenterOf(target.getBlockPos()));
            if (sublevel == null) return rayParameter(null, direction, localDistanceSquared);
            if (interactionRenderPose == null) interactionRenderPose = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel")
                    .getMethod("renderPose", float.class);
            return rayParameter(interactionRenderPose.invoke(sublevel, partialTick), direction, localDistanceSquared);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    private static Double rayParameter(Object pose, Vec3 direction, double localDistanceSquared) {
        if (!Double.isFinite(localDistanceSquared) || localDistanceSquared < 0) return null;
        try {
            Vec3 localDirection = direction;
            if (pose != null) {
                if (inverseNormal == null) inverseNormal = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc")
                        .getMethod("transformNormalInverse", Vec3.class);
                localDirection = (Vec3) inverseNormal.invoke(pose, direction);
            }
            double lengthSquared = localDirection.lengthSqr();
            if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0E-12) return null;
            // Inverse scaling changes the ray's length. The addon reports
            // |localDirection * t|^2, so recover t before using the world ray.
            double parameter = Math.sqrt(localDistanceSquared / lengthSquared);
            return Double.isFinite(parameter) ? parameter : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Whether a pick run outside Sable's render-level wrapper can still see
     * the frame's interpolated sublevel poses: true without Sable (there are
     * no sublevels), true when the pose bridge resolved, false when Sable is
     * present but the bridge is not — then the caller must keep vanilla's
     * ordering rather than clip ships at a stale pose.
     */
    static boolean canPushRenderPoses() {
        if (poseBridge == 0) {
            initPoseBridge();
        }
        return poseBridge != 3;
    }

    /**
     * Run {@code action} with Sable's sublevel clip poses set to the render
     * pose for {@code partialTick} (a plain call without Sable). Mirrors
     * Sable's own wrapper around the render-level pick: push, run, pop.
     */
    static void withRenderPoses(float partialTick, Runnable action) {
        if (poseBridge == 0) {
            initPoseBridge();
        }
        Object level = Minecraft.getInstance().level;
        if (poseBridge != 2 || level == null || !poseProviderExtension.isInstance(level)) {
            action.run();
            return;
        }
        it.unimi.dsi.fastutil.Function<Object, Object> supplier = subLevel -> {
            try {
                return renderPose.invoke(subLevel, partialTick);
            } catch (Throwable t) {
                // Not a client sublevel — unreachable on the client (Sable's
                // own supplier casts unconditionally); fall back to the tick
                // pose rather than abort the frame.
                try {
                    return logicalPose.invoke(subLevel);
                } catch (Throwable t2) {
                    throw new IllegalStateException("Sable sublevel pose unavailable", t2);
                }
            }
        };
        try {
            pushPoseSupplier.invoke(level, supplier);
        } catch (Throwable t) {
            action.run();
            return;
        }
        try {
            action.run();
        } finally {
            try {
                popPoseSupplier.invoke(level);
            } catch (Throwable ignored) {
                // Nothing sensible to do mid-frame; the next push/pop pair is independent.
            }
        }
    }

    private static void initPoseBridge() {
        if (!initialized) {
            init();
        }
        if (!sablePresent) {
            poseBridge = 1;
            return;
        }
        try {
            poseProviderExtension = Class.forName(
                    "dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension");
            pushPoseSupplier = poseProviderExtension.getMethod(
                    "sable$pushPoseSupplier", it.unimi.dsi.fastutil.Function.class);
            popPoseSupplier = poseProviderExtension.getMethod("sable$popPoseSupplier");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> clientSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            renderPose = lookup.unreflect(clientSubLevel.getMethod("renderPose", float.class));
            Class<?> subLevel = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            logicalPose = lookup.unreflect(subLevel.getMethod("logicalPose"));
            poseBridge = 2;
        } catch (Throwable t) {
            poseProviderExtension = null;
            pushPoseSupplier = null;
            popPoseSupplier = null;
            renderPose = null;
            logicalPose = null;
            poseBridge = 3;
        }
    }
}
