package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Failed/different-only paired free-look review, isolated from the original full-sweep evidence. */
public final class StormsEdgeUseRecheckProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.ItemUseProbe";
    private static final String HELPER = "com.caleb.unlockedcamera.qa.SimplySwordsQaIsolation";
    private static Class<?> loaded;
    private StormsEdgeUseRecheckProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        checkIdle(ItemUseProbe.class);
        checkWrapperIdle(ItemUseFreelookProbe.class);
        checkWrapperIdle(ItemUseReviewProbe.class);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(StormsEdgeUseRecheckProbe.class.getClassLoader()) {
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
        loaded = loader.loadClass(TYPE);
        Consumer<String> reviewLog = line -> log.accept(line.startsWith("ITEM_AIR_USE ")
                ? "STORMS_EDGE_RECHECK " + line.substring("ITEM_AIR_USE ".length()) : line);
        loaded.getMethod("runStormsEdgeRecheck", Minecraft.class, Consumer.class).invoke(null, mc, reviewLog);
    }

    private static void checkWrapperIdle(Class<?> wrapper) throws Exception {
        var field = wrapper.getDeclaredField("loaded"); field.setAccessible(true);
        Class<?> controller = (Class<?>) field.get(null);
        if (controller != null) checkIdle(controller);
    }

    private static void checkIdle(Class<?> controller) throws Exception {
        var active = controller.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("An item-use sweep is still running");
    }
}
