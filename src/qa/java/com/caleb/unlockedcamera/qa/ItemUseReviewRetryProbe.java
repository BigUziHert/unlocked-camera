package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Reloads the review controller AND its QA lifecycle helper after fixture corrections. */
public final class ItemUseReviewRetryProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.ItemUseProbe";
    private static final String HELPER = "com.caleb.unlockedcamera.qa.SimplySwordsQaIsolation";
    private ItemUseReviewRetryProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        checkIdle(ItemUseProbe.class);
        var loadedField = ItemUseReviewProbe.class.getDeclaredField("loaded"); loadedField.setAccessible(true);
        Class<?> previous = (Class<?>) loadedField.get(null);
        if (previous != null) checkIdle(previous);
        ClassLoader loader = new ClassLoader(ItemUseReviewRetryProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")
                        && !name.equals(HELPER) && !name.startsWith(HELPER + "$")) return super.loadClass(name, resolve);
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
        Class<?> controller = loader.loadClass(TYPE);
        loadedField.set(null, controller);
        controller.getMethod("runReviewRemaining", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var active = type.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("Item-use sweep is still running");
    }
}
