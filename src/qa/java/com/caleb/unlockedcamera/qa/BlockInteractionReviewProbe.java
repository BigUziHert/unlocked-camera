package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Loads the updated isolated recheck controller without restarting a running QA client. */
public final class BlockInteractionReviewProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.BlockInteractionRecheck";
    private static Class<?> loaded;
    private BlockInteractionReviewProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable copies");
        if (BlockInteractionProbe.isRunning()) throw new IllegalStateException("Broad block sweep is still active");
        for (Class<?> type : new Class<?>[]{BlockInteractionRecheck.class, loaded}) {
            if (type == null) continue;
            var field = type.getDeclaredField("running"); field.setAccessible(true);
            if (field.getBoolean(null)) throw new IllegalStateException("A block recheck is still active");
        }
        ClassLoader loader = new ClassLoader(BlockInteractionReviewProbe.class.getClassLoader()) {
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
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        loaded = loader.loadClass(TYPE);
        loaded.getMethod("run", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
}
