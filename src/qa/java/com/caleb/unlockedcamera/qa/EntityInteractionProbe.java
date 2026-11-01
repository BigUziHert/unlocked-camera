package com.caleb.unlockedcamera.qa;

import com.caleb.unlockedcamera.client.ClientConfig;
import com.caleb.unlockedcamera.client.UnlockedCameraClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** All registered default entity factories and targetability; no AI ticks, attacks or interactions. */
public final class EntityInteractionProbe {
    private EntityInteractionProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.isSameThread() || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null
                || mc.player.isPassenger() || mc.player.isUsingItem()) {
            throw new IllegalStateException("Entity probe requires idle local unmounted singleplayer");
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Method position = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
        Method rotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
        position.setAccessible(true);
        rotation.setAccessible(true);
        Vec3 playerPosition = mc.player.position(), cameraPosition = camera.getPosition();
        float bodyYaw = mc.player.getYRot(), bodyPitch = mc.player.getXRot();
        float cameraYaw = camera.getYRot(), cameraPitch = camera.getXRot(), cameraRoll = camera.getRoll();
        CameraType cameraType = mc.options.getCameraType();
        boolean toggle = ClientConfig.TOGGLE_MOD.get(), shoulder = ClientConfig.ENABLE_SHOULDER_OFFSET.get();
        double shoulderMax = ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.get();
        Map<Field, Object> state = new LinkedHashMap<>();
        for (String name : new String[] {"active", "targetDistance", "freelookYaw", "freelookPitch"}) {
            Field f = field(name);
            state.put(f, f.get(null));
        }
        var types = new ArrayList<EntityType<?>>();
        BuiltInRegistries.ENTITY_TYPE.forEach(types::add);
        types.sort(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()));
        Path directory = Path.of("qa", "registry-entities");
        Files.createDirectories(directory);
        int factories = 0, picked = 0, notPickable = 0, failures = 0, unavailable = 0;
        log.accept("REGISTRY_ENTITIES START registered=" + types.size() + " scope=default-client-targetability gameplay=NOT_EXECUTED");
        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve("targetability.csv"))) {
            row(out, "entity", "class", "pickable", "width", "height", "baseline", "shoulder_left",
                    "shoulder_right", "freelook_left", "freelook_right", "gameplay", "detail");
            ClientConfig.TOGGLE_MOD.set(true);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(true);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(5.5);
            field("targetDistance").setFloat(null, 4);
            // Above world height in the current loaded chunks. All changes are synchronous
            // client-side; no movement packet, entity tick or server spawn occurs.
            mc.player.setPos(playerPosition.x, mc.level.getMaxBuildHeight() + 64, playerPosition.z);
            mc.player.setYRot(0);
            mc.player.setXRot(0);
            for (EntityType<?> type : types) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                Entity entity = null;
                boolean added = false;
                try {
                    entity = type.create(mc.level);
                    if (entity == null) {
                        unavailable++;
                        row(out, id, "", "", "", "", "NO_DEFAULT_FACTORY", "NOT_RUN", "NOT_RUN",
                                "NOT_RUN", "NOT_RUN", "NOT_EXECUTED", "requires a specialized spawn context");
                        continue;
                    }
                    factories++;
                    Vec3 eye = mc.player.getEyePosition();
                    Vec3 center = eye.add(0, 0, 2.25);
                    entity.setPos(center.x, center.y - entity.getBbHeight() / 2.0, center.z);
                    mc.level.addEntity(entity);
                    added = true;
                    // Entity constructors such as hanging entities may alter their box/center.
                    center = entity.getBoundingBox().getCenter();
                    Vec3 direction = center.subtract(eye).normalize();
                    double range = Math.max(mc.player.entityInteractionRange(), 3.0);
                    EntityHitResult baseline = ProjectileUtil.getEntityHitResult(mc.player, eye,
                            eye.add(direction.scale(range)), mc.player.getBoundingBox()
                                    .expandTowards(direction.scale(range)).inflate(1),
                            candidate -> !candidate.isSpectator() && candidate.isPickable(), range * range);
                    boolean expected = baseline != null && baseline.getEntity() == entity;
                    if (expected) picked++; else notPickable++;
                    String[] modes = {"shoulder_left", "shoulder_right", "freelook_left", "freelook_right"};
                    String[] results = new String[4];
                    boolean failed = false;
                    for (int i = 0; i < modes.length; i++) {
                        String mode = modes[i];
                        boolean free = mode.startsWith("freelook"), left = mode.endsWith("left");
                        field("active").setBoolean(null, !free);
                        field("freelookYaw").setFloat(null, free ? (left ? -60 : 60) : 0);
                        field("freelookPitch").setFloat(null, 0);
                        mc.options.setCameraType(free ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
                        Vec3 origin = free ? eye : eye.add(left ? -.75 : .75, 0, -4);
                        Vec3 forward = center.subtract(origin).normalize();
                        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
                        float pitch = (float) -Math.toDegrees(Math.atan2(forward.y, Math.hypot(forward.x, forward.z)));
                        position.invoke(camera, origin);
                        rotation.invoke(camera, yaw, pitch, 0f);
                        HitResult hit = UnlockedCameraClient.cameraRayPick(mc.player, range, range, 1);
                        boolean actual = hit instanceof EntityHitResult entityHit && entityHit.getEntity() == entity;
                        // Some default entities deliberately have no selectable hitbox. A
                        // match there is an exclusion check, not a successful interaction.
                        boolean matches = actual == expected;
                        results[i] = matches ? expected ? "PASS_TARGET" : "PASS_DEFAULT_EXCLUSION" : "FAIL_TARGET_PARITY";
                        if (!matches) {
                            failed = true;
                            log.accept("REGISTRY_ENTITIES FAIL entity=" + id + " mode=" + mode + " expected=" + expected
                                    + " actual=" + actual + " hit=" + (hit == null ? "null" : hit.getType()));
                        }
                    }
                    if (failed) failures++;
                    row(out, id, entity.getClass().getName(), entity.isPickable(), entity.getBbWidth(), entity.getBbHeight(),
                            expected ? "TARGETABLE" : "NOT_TARGETABLE_DEFAULT", results[0], results[1], results[2], results[3],
                            "NOT_EXECUTED", "default factory only; no AI ticks, variants, multipart setup or server interactions");
                } catch (Throwable failure) {
                    failures++;
                    while (failure.getCause() != null) failure = failure.getCause();
                    row(out, id, entity == null ? "" : entity.getClass().getName(), "", "", "", "ERROR",
                            "NOT_RUN", "NOT_RUN", "NOT_RUN", "NOT_RUN", "NOT_EXECUTED", failure);
                    log.accept("REGISTRY_ENTITIES ERROR entity=" + id + " " + failure);
                } finally {
                    if (added && entity != null) mc.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        } finally {
            mc.player.setPos(playerPosition.x, playerPosition.y, playerPosition.z);
            mc.player.setYRot(bodyYaw);
            mc.player.setXRot(bodyPitch);
            state.forEach((f, value) -> { try { f.set(null, value); } catch (Exception e) { throw new RuntimeException(e); } });
            ClientConfig.TOGGLE_MOD.set(toggle);
            ClientConfig.ENABLE_SHOULDER_OFFSET.set(shoulder);
            ClientConfig.SHOULDER_OFFSET_MAX_ZOOM.set(shoulderMax);
            mc.options.setCameraType(cameraType);
            rotation.invoke(camera, cameraYaw, cameraPitch, cameraRoll);
            position.invoke(camera, cameraPosition);
        }
        String summary = "REGISTRY_ENTITIES DONE registered=" + types.size() + " constructed=" + factories
                + " targetable=" + picked + " defaultNotTargetable=" + notPickable + " noDefaultFactory=" + unavailable
                + " failedTypes=" + failures + " gameplay=NOT_EXECUTED";
        Files.writeString(directory.resolve("summary.txt"), summary + System.lineSeparator());
        log.accept(summary);
    }

    private static Field field(String name) throws Exception {
        Field f = UnlockedCameraClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static void row(BufferedWriter writer, Object... cells) throws Exception {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) writer.write(',');
            writer.write('"');
            writer.write(String.valueOf(cells[i]).replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' '));
            writer.write('"');
        }
        writer.newLine();
    }
}
