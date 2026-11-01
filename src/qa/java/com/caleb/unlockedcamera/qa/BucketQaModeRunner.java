package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Fresh disposable controller for bounded post-fix bucket variants. */
final class BucketQaModeRunner {
    private static Class<?> active;
    private BucketQaModeRunner() {}
    static void run(Minecraft mc, Consumer<String> log, String method, String marker) throws Exception {
        checkIdle(BucketUseProbe.class);
        if (active != null) checkIdle(active);
        active = fresh("com.caleb.unlockedcamera.qa.BucketUseProbe");
        Consumer<String> output = line -> log.accept(line.startsWith("BUCKET_USE ")
                ? marker + " " + line.substring("BUCKET_USE ".length()) : line);
        active.getMethod(method, Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
    static Class<?> fresh(String type) throws Exception {
        return new ClassLoader(BucketQaModeRunner.class.getClassLoader()) {
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
        }.loadClass(type);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var field = type.getDeclaredField("running"); field.setAccessible(true);
        if (field.getBoolean(null)) throw new IllegalStateException("Bucket controller is still active");
    }
}
