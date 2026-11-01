package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Reloads only the opt-in QA controller so a running test client needs no restart. */
public final class MeleeRemainingProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.MeleeBaselineProbe";
    private static Class<?> loaded;
    private MeleeRemainingProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        var previous = MeleeBaselineProbe.class.getDeclaredField("running"); previous.setAccessible(true);
        if (previous.getBoolean(null)) throw new IllegalStateException("Previous melee probe still running");
        if (loaded != null) {
            var active = loaded.getDeclaredField("running"); active.setAccessible(true);
            if (active.getBoolean(null)) throw new IllegalStateException("Remaining melee probe still running");
        }
        Path evidence = Path.of("qa", "melee-recheck.tsv");
        if (Files.exists(evidence)) Files.copy(evidence, Path.of("qa", "melee-recheck-before-tail-" + System.currentTimeMillis() + ".tsv"));
        ClassLoader loader = new ClassLoader(MeleeRemainingProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        String resource = name.replace('.', '/') + ".class";
                        try (InputStream stream = getParent().getResourceAsStream(resource)) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes();
                            result = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                    }
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        loaded = loader.loadClass(TYPE);
        loaded.getMethod("runRemaining", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }
}
