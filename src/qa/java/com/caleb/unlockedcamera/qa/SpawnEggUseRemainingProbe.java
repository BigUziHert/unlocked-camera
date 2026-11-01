package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Failed-ID-only fresh three-view real egg requests, preserving the full sweep. */
public final class SpawnEggUseRemainingProbe {
    private static Class<?> active;
    private SpawnEggUseRemainingProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        checkIdle(SpawnEggUseProbe.class);
        if (active != null) checkIdle(active);
        String type = "com.caleb.unlockedcamera.qa.SpawnEggUseProbe";
        ClassLoader loader = new ClassLoader(SpawnEggUseRemainingProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(type) && !name.startsWith(type + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes(); result = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(result); return result;
                }
            }
        };
        active = loader.loadClass(type);
        Consumer<String> output = line -> log.accept(line.startsWith("SPAWN_EGG_USE ")
                ? "SPAWN_EGG_RECHECK " + line.substring("SPAWN_EGG_USE ".length()) : line);
        active.getMethod("runReviewRemaining", Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var field = type.getDeclaredField("running"); field.setAccessible(true);
        if (field.getBoolean(null)) throw new IllegalStateException("Spawn egg controller is still active");
    }
}
