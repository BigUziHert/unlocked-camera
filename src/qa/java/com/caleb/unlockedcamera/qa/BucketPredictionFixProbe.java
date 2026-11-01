package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Full post-fix bucket actions in a separate artifact directory. */
public final class BucketPredictionFixProbe {
    private static Class<?> active;
    private BucketPredictionFixProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        checkIdle(BucketUseProbe.class);
        if (active != null) checkIdle(active);
        String type = "com.caleb.unlockedcamera.qa.BucketUseProbe";
        ClassLoader loader = new ClassLoader(BucketPredictionFixProbe.class.getClassLoader()) {
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
        Consumer<String> output = line -> log.accept(line.startsWith("BUCKET_USE ")
                ? "BUCKET_PREDICTION_FIX " + line.substring("BUCKET_USE ".length()) : line);
        active.getMethod("runPredictionFix", Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var field = type.getDeclaredField("running"); field.setAccessible(true);
        if (field.getBoolean(null)) throw new IllegalStateException("Bucket controller is still active");
    }
}
