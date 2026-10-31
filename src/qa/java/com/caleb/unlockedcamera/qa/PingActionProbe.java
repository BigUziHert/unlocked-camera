package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Exercises PingController -> C2S -> integrated server -> S2C -> displayed ping. */
public final class PingActionProbe {
    private static Consumer<String> log;
    private static List<?> repo;
    private static Set<Object> before;
    private static Vec3 expected;
    private static UUID entity;
    private static Field position;
    private static int remaining;

    public static void start(Minecraft mc, Consumer<String> output) throws Exception {
        if (mc.getSingleplayerServer() == null || UnlockedCameraClient.crosshairRayDirection(mc.player) == null)
            throw new IllegalStateException("Ping test requires an active camera in the isolated singleplayer world");
        mc.gameRenderer.pick(1);
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) throw new IllegalStateException("Aim at a block or entity first");
        log = output;
        Vec3 vanillaHit = hit.getLocation();
        String shape = "entity";
        if (!(hit instanceof EntityHitResult)) {
            // Ping Wheel intentionally uses VISUAL/NONE normally and
            // OUTLINE/ANY while crouching, unlike vanilla's OUTLINE pick.
            // A Create seat's visual/collision shape is lower than its outline.
            Class<?> controller = Class.forName("nx.pingwheel.common.core.PingController");
            Field configField = controller.getDeclaredField("CLIENT_CONFIG");
            configField.setAccessible(true);
            Object config = configField.get(null);
            int range = Math.min((int) config.getClass().getMethod("getRaycastDistance").invoke(config),
                    (int) config.getClass().getMethod("getPingDistance").invoke(config));
            boolean crouching = mc.getCameraEntity().isCrouching();
            Vec3 origin = UnlockedCameraClient.crosshairRayGapFreeOrigin(mc.player);
            Vec3 end = origin.add(UnlockedCameraClient.crosshairRayDirection(mc.player).scale(range));
            hit = mc.level.clip(new ClipContext(origin, end,
                    crouching ? ClipContext.Block.OUTLINE : ClipContext.Block.VISUAL,
                    crouching ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.getCameraEntity()));
            shape = crouching ? "OUTLINE/ANY" : "VISUAL/NONE";
            if (hit.getType() == HitResult.Type.MISS)
                throw new IllegalStateException("Ping's " + shape + " ray misses this outline-only fixture");
        }
        expected = hit.getLocation();
        entity = hit instanceof EntityHitResult e ? e.getEntity().getUUID() : null;
        if (expected.distanceToSqr(mc.player.position()) > 256 * 256) {
            Class<?> companion = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
            expected = (Vec3) companion.getMethod("projectOutOfSubLevel", Level.class, net.minecraft.core.Position.class)
                    .invoke(companion.getField("INSTANCE").get(null), mc.level, expected);
        }
        repo = (List<?>) Class.forName("nx.pingwheel.common.core.PingManager").getField("PING_REPO").get(null);
        before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(repo);
        position = Class.forName("nx.pingwheel.common.core.PingData").getDeclaredField("pos");
        position.setAccessible(true);
        Method perform = Class.forName("nx.pingwheel.common.core.PingController").getDeclaredMethod("performPingAction", float.class);
        perform.setAccessible(true);
        float yaw = mc.player.getYRot(), previousYaw = mc.player.yRotO;
        float pitch = mc.player.getXRot(), previousPitch = mc.player.xRotO;
        try {
            // Deliberately disagree with the camera for this synchronous call.
            // Without our PingController direction hook the test ray goes sideways.
            mc.player.setYRot(yaw + 90); mc.player.yRotO = yaw + 90;
            perform.invoke(null, 1.0f);
            remaining = 40;
            log.accept("PING_ACTION SENT expected=" + expected + " entity=" + entity
                    + " shape=" + shape + " vanillaRawHit=" + vanillaHit + " bodyDeflection=90");
        } finally {
            mc.player.setYRot(yaw); mc.player.yRotO = previousYaw;
            mc.player.setXRot(pitch); mc.player.xRotO = previousPitch;
        }
    }

    public static void tick(Minecraft mc) {
        if (remaining <= 0) return;
        try {
            for (Object ping : repo) {
                if (before.contains(ping) || !mc.player.getUUID().equals(ping.getClass().getField("authorId").get(ping))) continue;
                Vec3 actual = (Vec3) position.get(ping);
                Object actualEntity = ping.getClass().getField("entityId").get(ping);
                double error = actual.distanceTo(expected);
                boolean pass = entity == null ? actualEntity == null && error < .15 : entity.equals(actualEntity);
                log.accept("PING_ACTION " + (pass ? "PASS" : "FAIL") + " received=" + actual
                        + " positionError=" + error + " entity=" + actualEntity);
                remaining = 0;
                return;
            }
            if (--remaining == 0) log.accept("PING_ACTION FAIL no new server-echoed ping within 40 ticks");
        } catch (Exception e) { remaining = 0; log.accept("PING_ACTION ERROR " + e); }
    }
}
