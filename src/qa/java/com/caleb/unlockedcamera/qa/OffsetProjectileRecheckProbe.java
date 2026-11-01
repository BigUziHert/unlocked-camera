package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Fresh full offset suite using the real rendered camera, preserving the prior fixture attempt. */
public final class OffsetProjectileRecheckProbe {
    private static final String RUNNER = "com.caleb.unlockedcamera.qa.OffsetProjectileRegressionProbe";
    private static Class<?> loaded;
    private OffsetProjectileRecheckProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        checkIdle(OffsetProjectileRegressionProbe.class);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(OffsetProjectileRecheckProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(RUNNER) && !name.startsWith(RUNNER + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes(); result = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        loaded = loader.loadClass(RUNNER);
        Consumer<String> renamed = line -> log.accept(line.replace("PROJECTILE_OFFSET", "PROJECTILE_OFFSET_RECHECK")
                .replace("pose=direct_fixture;", "pose=natural_render_camera;"));
        loaded.getMethod("run", Minecraft.class, Consumer.class).invoke(null, mc, renamed);
        Class<?> core = (Class<?>) field(loaded, "active").get(null);
        // start() only schedules actions. Disable its manual Camera.setPosition
        // fixture before the first action/tick; renderer and normal camera hooks
        // now supply the same camera to production Pre and the shot expectation.
        // Keep standardRecheckMode for the ordered final server cleanup marker.
        field(core, "cameraPosition").set(null, null);
        log.accept("PROJECTILE_OFFSET_RECHECK_FIXTURE natural_render_camera=true manual_camera_position_disabled=true"
                + " standardRecheckMode=true scheduled_projectiles_and_oracles_unchanged=true"
                + " reason=prior_Post_fixture_used_opposite_horizontal_shoulder_sign;prior_rows_preserved");
    }

    private static void checkIdle(Class<?> runner) throws Exception {
        Class<?> core = (Class<?>) field(runner, "active").get(null);
        if (core == null) return;
        if (field(core, "running").getBoolean(null)) throw new IllegalStateException("Prior offset projectile suite is active");
        for (String name : new String[]{"serverTask", "observationTask"}) {
            CompletableFuture<?> pending = (CompletableFuture<?>) field(core, name).get(null);
            if (pending != null && !pending.isDone()) throw new IllegalStateException("Prior offset callback still draining: " + name);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
