package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Fresh bounded staff scenario; preserves the prior cached-target attempt. */
public final class PhysicsStaffRecheckProbe {
    private static final String SESSION = "com.caleb.unlockedcamera.qa.PhysicsStaffSession";
    private static Class<?> active;
    private PhysicsStaffRecheckProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception { runMode(mc, log, false); }
    public static void runFreelook(Minecraft mc, Consumer<String> log) throws Exception { runMode(mc, log, true); }

    private static void runMode(Minecraft mc, Consumer<String> log, boolean freelook) throws Exception {
        if (!mc.isSameThread()) throw new IllegalStateException("Client-thread startup required");
        idle(PhysicsStaffSession.class);
        if (active != null) idle(active);
        if (field(SableMotionProbe.class, "context").get(null) != null)
            throw new IllegalStateException("Prior Sable motion has not restored");
        ClassLoader loader = new ClassLoader(PhysicsStaffRecheckProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(SESSION) && !name.startsWith(SESSION + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> value = findLoadedClass(name);
                    if (value == null) {
                        try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes(); value = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(value);
                    return value;
                }
            }
        };
        active = loader.loadClass(SESSION);
        String before = freelook ? "PHYSICS_STAFF_FREELOOK" : "PHYSICS_STAFF";
        String marker = before + "_RECHECK";
        Consumer<String> renamed = line -> log.accept(line.startsWith(before) ? marker + line.substring(before.length()) : line);
        log.accept(marker + "_FIXTURE fresh_client_logical_target_at_pick=true independent_reference=OUTLINE/NONE"
                + " same18checks_and_server_drag_oracles=true prior_attempt_preserved=true");
        active.getMethod("run", Minecraft.class, Consumer.class, boolean.class).invoke(null, mc, renamed, freelook);
    }

    private static void idle(Class<?> session) throws Exception {
        if (field(session, "running").getBoolean(null)) throw new IllegalStateException("Prior staff scenario still active");
        Object pending = field(session, "pending").get(null);
        if (pending instanceof CompletableFuture<?> future && !future.isDone()) throw new IllegalStateException("Prior staff packet callback still draining");
        for (Object value : (List<?>) field(session, "serverCallbacks").get(null))
            if (value instanceof CompletableFuture<?> future && !future.isDone()) throw new IllegalStateException("Prior staff server callback still draining");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
