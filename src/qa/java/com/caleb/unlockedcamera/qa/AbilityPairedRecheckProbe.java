package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Fresh QA runner/core; two real key presses with matched camera geometry. */
public final class AbilityPairedRecheckProbe {
    private static Class<?> runner;
    private AbilityPairedRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        checkIdle(WeaponModeRunner.class);
        if (runner != null) checkIdle(runner);
        String type = "com.caleb.unlockedcamera.qa.WeaponModeRunner";
        ClassLoader loader = new ClassLoader(AbilityPairedRecheckProbe.class.getClassLoader()) {
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
        runner = loader.loadClass(type);
        runner.getMethod("start", Minecraft.class, Consumer.class, String.class).invoke(null, mc, log, "pairedAbilityRecheck");
    }
    private static void checkIdle(Class<?> runnerType) throws Exception {
        var field = runnerType.getDeclaredField("active"); field.setAccessible(true);
        Class<?> core = (Class<?>) field.get(null);
        if (core == null) return;
        var running = core.getDeclaredField("running"); running.setAccessible(true);
        if (running.getBoolean(null)) throw new IllegalStateException("An integration weapon suite is still active");
    }
}
