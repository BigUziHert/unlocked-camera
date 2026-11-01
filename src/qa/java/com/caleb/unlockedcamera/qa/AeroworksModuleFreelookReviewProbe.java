package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Fresh controller for bounded, paired diagnostics after the immutable full AeroWorks sweep. */
public final class AeroworksModuleFreelookReviewProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.AeroworksModuleProbe";
    private static Class<?> loaded;
    private AeroworksModuleFreelookReviewProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Only disposable QA copies may run the AeroWorks review");
        for (Class<?> type : new Class<?>[]{AeroworksModuleProbe.class, loaded}) {
            if (type == null) continue;
            var running = type.getDeclaredField("running"); running.setAccessible(true);
            if (running.getBoolean(null)) throw new IllegalStateException("Wait for the active AeroWorks sweep/review to restore");
        }
        ClassLoader loader = new ClassLoader(AeroworksModuleFreelookReviewProbe.class.getClassLoader()) {
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
        loaded.getMethod("runFreelookReview", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
}
