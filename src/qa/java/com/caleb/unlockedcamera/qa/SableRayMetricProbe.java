package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Nonmutating analytical checks of the production console clip-distance conversion. */
public final class SableRayMetricProbe {
    private SableRayMetricProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Class<?> poseType = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
        Class<?> poseView = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
        Method transform = poseView.getMethod("transformPosition", Vec3.class);
        Method inverse = poseView.getMethod("transformPositionInverse", Vec3.class);
        Method inverseNormal = poseView.getMethod("transformNormalInverse", Vec3.class);
        Method production = Class.forName("com.caleb.unlockedcamera.client.SableCompat")
                .getDeclaredMethod("rayParameter", Object.class, Vec3.class, double.class);
        production.setAccessible(true);
        Vec3 origin = new Vec3(7, 3, -4);
        Vec3[] directions = {new Vec3(0, 0, 1), new Vec3(1, .4, 1).normalize(), new Vec3(-.7, -.3, .2).normalize()};
        String[] names = {"identity", "translation", "rotation", "uniform_scale", "nonuniform_scale"};
        int checks = 0, failures = 0;
        log.accept("SABLE_RAY_METRIC START scope=analytical-installed-pose-and-production-distance-helper worldMutation=false gameplayRequests=0");
        for (int p = 0; p < names.length; p++) {
            Vector3d position = p == 0 ? new Vector3d() : new Vector3d(40, 12, -70);
            Quaterniond orientation = p < 2 ? new Quaterniond() : new Quaterniond().rotateXYZ(.23, -.65, .41);
            Vector3d pivot = p == 0 ? new Vector3d() : new Vector3d(1_000_000, 128, -1_000_000);
            Vector3d scale = p == 3 ? new Vector3d(2) : p == 4 ? new Vector3d(.5, 2, 1.5) : new Vector3d(1);
            Object pose = poseType.getConstructor(Vector3d.class, Quaterniond.class, Vector3d.class, Vector3d.class)
                    .newInstance(position, orientation, pivot, scale);
            for (int ray = 0; ray < directions.length; ray++) for (double distance : new double[]{1.5, 4.5, 6.5}) {
                Vec3 direction = directions[ray];
                Vec3 localStart = (Vec3) inverse.invoke(pose, origin);
                Vec3 localDirection = (Vec3) inverseNormal.invoke(pose, direction);
                Vec3 localCenter = (Vec3) inverse.invoke(pose, origin.add(direction.scale(distance)));
                AABB bounds = new AABB(localCenter.subtract(.04, .04, .04), localCenter.add(.04, .04, .04));
                Vec3 localHit = bounds.clip(localStart, localStart.add(localDirection.scale(8))).orElseThrow();
                Double parameter = (Double) production.invoke(null, pose, direction, localStart.distanceToSqr(localHit));
                Vec3 expectedWorldHit = (Vec3) transform.invoke(pose, localHit);
                double error = parameter == null ? Double.POSITIVE_INFINITY
                        : origin.add(direction.scale(parameter)).distanceTo(expectedWorldHit);
                boolean sameReach = parameter != null && (parameter <= 4.5) == (expectedWorldHit.distanceTo(origin) <= 4.5);
                boolean pass = error < 1.0E-7 && sameReach;
                checks++; if (!pass) failures++;
                log.accept("SABLE_RAY_METRIC " + (pass ? "PASS" : "FAIL") + " " + names[p] + " ray=" + ray
                        + " nominalDistance=" + distance + " worldError=" + error + " sameReach=" + sameReach
                        + " parameter=" + parameter);
            }
        }
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean pass = production.invoke(null, null, directions[0], invalid) == null;
            checks++; if (!pass) failures++;
            log.accept("SABLE_RAY_METRIC " + (pass ? "PASS" : "FAIL") + " invalid-distance=" + invalid);
        }
        boolean zero = production.invoke(null, null, Vec3.ZERO, 1d) == null;
        checks++; if (!zero) failures++;
        log.accept("SABLE_RAY_METRIC " + (zero ? "PASS" : "FAIL") + " zero-direction");
        log.accept("SABLE_RAY_METRIC DONE checks=" + checks + " failures=" + failures);
        log.accept("SABLE_RAY_METRIC RESTORED stateUntouched=true");
    }
}
