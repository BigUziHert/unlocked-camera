package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Optional QA source: exercises transformed mod classes without attacking or saving entities. */
public final class CompatibilityProbe {
    private CompatibilityProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.isSameThread() || mc.player == null || mc.level == null) {
            throw new IllegalStateException("Compatibility probe requires an active client world");
        }
        inspectSubLevels(mc.level, "client", log);
        if (mc.getSingleplayerServer() != null) {
            mc.getSingleplayerServer().execute(() -> {
                for (var level : mc.getSingleplayerServer().getAllLevels()) {
                    try {
                        inspectSubLevels(level, "server:" + level.dimension().location(), log);
                    } catch (Exception e) { log.accept("SABLE_INVENTORY ERROR " + e); }
                }
            });
        }
        Class<?> bridge = Class.forName("com.caleb.unlockedcamera.client.SableCompat");
        Method canPush = bridge.getDeclaredMethod("canPushRenderPoses");
        canPush.setAccessible(true);
        Method withPoses = bridge.getDeclaredMethod("withRenderPoses", float.class, Runnable.class);
        withPoses.setAccessible(true);
        boolean[] invoked = {false};
        boolean can = (boolean) canPush.invoke(null);
        withPoses.invoke(null, .5f, (Runnable) () -> invoked[0] = true);
        log.accept("SABLE_BRIDGE ready=" + can + " state=" + field(bridge, "poseBridge").get(null)
                + " actionInvoked=" + invoked[0]);
        probeBetterCombat(mc, log);
    }

    private static void inspectSubLevels(Level level, String side, Consumer<String> log) throws Exception {
        Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Object container = containerClass.getMethod("getContainer", Level.class).invoke(null, level);
        List<?> sublevels = (List<?>) containerClass.getMethod("getAllSubLevels").invoke(container);
        log.accept("SABLE_INVENTORY side=" + side + " count=" + sublevels.size());
        int number = 0;
        for (Object sub : sublevels) {
            if (++number > 24) break;
            Class<?> type = sub.getClass();
            Object pose = type.getMethod("logicalPose").invoke(sub);
            Object plot = type.getMethod("getPlot").invoke(sub);
            Object bounds = type.getMethod("boundingBox").invoke(sub);
            log.accept("SABLE_SUBLEVEL side=" + side + " id=" + type.getMethod("getUniqueId").invoke(sub)
                    + " name=" + type.getMethod("getName").invoke(sub) + " bounds=" + bounds
                    + " pose=" + pose + " plotBounds=" + plot.getClass().getMethod("getBoundingBox").invoke(plot));
            Method transform = pose.getClass().getMethod("transformPosition", Vec3.class);
            Collection<?> chunks = (Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
            int markers = 0;
            int scanned = 0;
            for (Object holder : chunks) {
                LevelChunk chunk = (LevelChunk) holder.getClass().getMethod("getChunk").invoke(holder);
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (markers++ >= 64) break;
                    BlockPos pos = entry.getKey();
                    log.accept("SABLE_BLOCK_ENTITY side=" + side + " plot=" + pos + " world="
                            + transform.invoke(pose, pos.getCenter()) + " block="
                            + BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlockState().getBlock()));
                }
                for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
                    var section = chunk.getSection(sectionIndex);
                    if (section.hasOnlyAir()) continue;
                    if ((scanned += 4096) > 262144 || markers >= 64) break;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (!id.contains("seat") && !id.contains("steering") && !id.contains("throttle")) continue;
                        if (markers++ >= 64) continue;
                        BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x,
                                level.getSectionYFromSectionIndex(sectionIndex) * 16 + y,
                                chunk.getPos().getMinBlockZ() + z);
                        log.accept("SABLE_CONTROL side=" + side + " plot=" + pos + " world="
                                + transform.invoke(pose, pos.getCenter()) + " block=" + id);
                    }
                }
                if (scanned > 262144 || markers >= 64) break;
            }
        }
    }

    private static void probeBetterCombat(Minecraft mc, Consumer<String> log) throws Exception {
        Class<?> registry = Class.forName("net.bettercombat.logic.WeaponRegistry");
        Object attributes = registry.getMethod("getAttributes", ItemStack.class)
                .invoke(null, new ItemStack(Items.DIAMOND_SWORD));
        if (attributes == null) {
            log.accept("BETTER_COMBAT SKIP diamond sword attributes unavailable");
            return;
        }
        Object attack = ((Object[]) attributes.getClass().getMethod("attacks").invoke(attributes))[0];
        Class<?> finder = Class.forName("net.bettercombat.client.collision.TargetFinder");
        Method find = finder.getMethod("findAttackTargetResult", Player.class, Entity.class, attack.getClass(), double.class);
        Method originMethod = finder.getMethod("getInitialTracingPoint", Player.class);
        Object config = Class.forName("net.bettercombat.BetterCombatMod").getField("config").get(null);
        Field throughWalls = config.getClass().getField("allow_attacking_thru_walls");
        boolean oldWalls = throughWalls.getBoolean(config);
        Field active = field(UnlockedCameraClient.class, "active");
        Field freelookYaw = field(UnlockedCameraClient.class, "freelookYaw");
        Field freelookPitch = field(UnlockedCameraClient.class, "freelookPitch");
        boolean oldActive = active.getBoolean(null);
        float oldFreelookYaw = freelookYaw.getFloat(null), oldFreelookPitch = freelookPitch.getFloat(null);
        boolean oldToggle = ClientConfig.TOGGLE_MOD.get();
        CameraType oldType = mc.options.getCameraType();
        Camera camera = mc.gameRenderer.getMainCamera();
        float oldCameraYaw = camera.getYRot(), oldCameraPitch = camera.getXRot();
        float oldBodyYaw = mc.player.getYRot(), oldBodyPitch = mc.player.getXRot();
        HitResult oldHit = mc.hitResult;
        Method rotate = Camera.class.getDeclaredMethod("setRotation", float.class, float.class);
        rotate.setAccessible(true);
        int failures = 0;
        try {
            // Synthetic cursor entities never enter either world and are never attacked.
            // Disable terrain clipping only inside this synchronous geometry probe.
            throughWalls.setBoolean(config, true);
            active.setBoolean(null, false);
            ClientConfig.TOGGLE_MOD.set(true);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            for (float[] angle : new float[][] {{90, 0}, {-90, 0}, {60, 30}, {-60, -30}}) {
                freelookYaw.setFloat(null, angle[0]);
                freelookPitch.setFloat(null, angle[1]);
                rotate.invoke(camera, angle[0], angle[1]);
                Vec3 origin = (Vec3) originMethod.invoke(null, mc.player);
                Vec3 desired = Vec3.directionFromRotation(angle[1], angle[0]);
                Vec3 center = origin.add(desired.scale(2));
                Entity target = EntityType.PIG.create(mc.level);
                if (target == null) throw new IllegalStateException("Could not make synthetic pig");
                target.setPos(center.x, center.y - target.getBbHeight() / 2.0, center.z);
                mc.hitResult = new EntityHitResult(target, center);
                Object result = find.invoke(null, mc.player, target, attack, 3.0);
                Object obb = result.getClass().getField("obb").get(result);
                Vec3 actual = (Vec3) obb.getClass().getField("axisZ").get(obb);
                boolean selected = ((List<?>) result.getClass().getField("entities").get(result)).contains(target);
                double dot = desired.normalize().dot(actual.normalize());
                boolean pass = dot > .99999 && selected && mc.player.getYRot() == 0 && mc.player.getXRot() == 0;
                if (!pass) failures++;
                log.accept("BETTER_COMBAT " + (pass ? "PASS" : "FAIL") + " yaw=" + angle[0]
                        + " pitch=" + angle[1] + " axisDot=" + dot + " targetSelected=" + selected);
            }
            // No deflection must preserve Better Combat's body-aligned behavior.
            freelookYaw.setFloat(null, 0);
            freelookPitch.setFloat(null, 0);
            Object result = find.invoke(null, mc.player, null, attack, 3.0);
            Object obb = result.getClass().getField("obb").get(result);
            Vec3 actual = (Vec3) obb.getClass().getField("axisZ").get(obb);
            boolean pass = actual.dot(Vec3.directionFromRotation(0, 0)) > .99999;
            if (!pass) failures++;
            log.accept("BETTER_COMBAT inactive=" + (pass ? "PASS" : "FAIL") + " totalCases=5 failures=" + failures);
        } finally {
            throughWalls.setBoolean(config, oldWalls);
            active.setBoolean(null, oldActive);
            freelookYaw.setFloat(null, oldFreelookYaw);
            freelookPitch.setFloat(null, oldFreelookPitch);
            ClientConfig.TOGGLE_MOD.set(oldToggle);
            mc.options.setCameraType(oldType);
            mc.player.setYRot(oldBodyYaw);
            mc.player.setXRot(oldBodyPitch);
            mc.hitResult = oldHit;
            rotate.invoke(camera, oldCameraYaw, oldCameraPitch);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
