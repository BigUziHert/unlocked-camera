package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Fresh offset projectile QA core; completion is emitted only after its real server cleanup. */
public final class OffsetProjectileRegressionProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.IntegrationSceneProbe";
    private static Class<?> active;
    private static Method tick, frame, spawn;
    private static boolean registered;
    private static Consumer<String> output;

    private OffsetProjectileRegressionProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("Offset projectile regression requires the disposable primary client");
        checkIdle(IntegrationSceneProbe.class);
        checkRunner(WeaponModeRunner.class);
        Class<?> pairedRunner = (Class<?>) field(AbilityPairedRecheckProbe.class, "runner").get(null);
        if (pairedRunner != null) checkRunner(pairedRunner);
        if (active != null) checkIdle(active);
        ClassLoader loader = new ClassLoader(OffsetProjectileRegressionProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")) return super.loadClass(name, resolve);
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
        active = loader.loadClass(TYPE);
        tick = active.getMethod("tick", Minecraft.class);
        frame = active.getMethod("afterFrame", RenderFrameEvent.Post.class);
        spawn = active.getMethod("projectileSpawn", EntityJoinLevelEvent.class);
        output = line -> {
            if (line.startsWith("INTEGRATION ")) log.accept("PROJECTILE_OFFSET_SUITE " + line.substring(12));
            else if (line.startsWith("PROJECTILE ")) log.accept("PROJECTILE_OFFSET " + line.substring(11));
            else log.accept("PROJECTILE_OFFSET_DETAIL " + line);
        };
        if (!registered) {
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> invoke(tick, Minecraft.getInstance()));
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> invoke(frame, event));
            NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> invoke(spawn, event));
            registered = true;
        }
        // The existing flag enables synchronized fixture camera/restoration handling.
        // It does not change the projectile list, fire paths, observations or pass criteria.
        field(active, "standardRecheckMode").setBoolean(null, true);
        log.accept("PROJECTILE_OFFSET_MODE THIRD_PERSON_BACK active=true shoulderOffset=.95 freelookKey=false"
                + " suite=full_projectiles baselineVariant=false freshQACore=true oracleUnchanged=true"
                + " terminal=PROJECTILE_OFFSET_SUITE_RESTORED_after_actual_server_cleanup");
        active.getMethod("projectiles", Minecraft.class, Consumer.class).invoke(null, mc, output);
    }

    private static void checkRunner(Class<?> runner) throws Exception {
        Class<?> core = (Class<?>) field(runner, "active").get(null);
        if (core != null) checkIdle(core);
    }

    private static void checkIdle(Class<?> core) throws Exception {
        if (field(core, "running").getBoolean(null)) throw new IllegalStateException("Another integration suite is active");
        for (String name : new String[]{"serverTask", "observationTask"}) {
            CompletableFuture<?> pending = (CompletableFuture<?>) field(core, name).get(null);
            if (pending != null && !pending.isDone()) throw new IllegalStateException("Another integration callback is still draining: " + name);
        }
    }

    private static void invoke(Method method, Object argument) {
        try {
            if (active != null && field(active, "running").getBoolean(null)) method.invoke(null, argument);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            output.accept("INTEGRATION ABORT callback " + cause);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
