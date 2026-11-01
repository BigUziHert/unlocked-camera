package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Reload only the opt-in failed-only freelook review controller in an idle QA copy. */
public final class BlockFreelookReviewProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.BlockFreelookRecheck";
    private static Class<?> loaded;
    private BlockFreelookReviewProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Freelook review is restricted to disposable QA copies");
        for (Class<?> type : new Class<?>[]{BlockFreelookProbe.class, BlockFreelookRecheck.class, loaded}) {
            if (type == null) continue;
            var running = type.getDeclaredField("running"); running.setAccessible(true);
            if (running.getBoolean(null)) throw new IllegalStateException("Wait for the current block sweep/review to finish");
        }
        ClassLoader loader = new ClassLoader(BlockFreelookReviewProbe.class.getClassLoader()) {
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
        loaded.getMethod("run", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
}
