package com.caleb.unlockedcamera.qa;
import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Separate retries for entity differences and QA cleanup errors; keeps original artifacts. */
public final class EntityActionReviewProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.EntityActionProbe";
    private static Class<?> loaded;
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        idle(EntityActionProbe.class); if (loaded != null) idle(loaded);
        ClassLoader loader = new ClassLoader(EntityActionReviewProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null) throw new ClassNotFoundException(name);
                            byte[] bytes = in.readAllBytes(); result = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(result); return result;
                }
            }
        };
        loaded = loader.loadClass(TYPE);
        loaded.getMethod("runReview", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
    private static void idle(Class<?> type) throws Exception {
        var running = type.getDeclaredField("running"); running.setAccessible(true);
        if (running.getBoolean(null)) throw new IllegalStateException("Entity action probe is still running");
    }
}
