package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Fresh QA-only controller; original timeout evidence is preserved separately. */
public final class CardboardKnockbackRecheckProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.CardboardKnockbackProbe";
    private static Class<?> loaded;
    private CardboardKnockbackRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        checkIdle(CardboardKnockbackProbe.class);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(CardboardKnockbackRecheckProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")) return super.loadClass(name, resolve);
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
        loaded.getMethod("runRecheck", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var active = type.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("Cardboard probe is still running");
    }
}
