package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Bounded fresh-core paired diagnostics; preserves original offset outcome rows. */
public final class MeleePathDiagnosticProbe {
    private static Class<?> runner;
    private MeleePathDiagnosticProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        checkIdle(WeaponModeRunner.class);
        if (runner != null) checkIdle(runner);
        // The ordinary runner was loaded by the resumed sweep. Reload its QA
        // mapping too, then let it load a fresh core and register its callbacks.
        String runnerName = "com.caleb.unlockedcamera.qa.WeaponModeRunner";
        ClassLoader loader = new ClassLoader(MeleePathDiagnosticProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(runnerName) && !name.startsWith(runnerName + "$")) return super.loadClass(name, resolve);
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
        runner = loader.loadClass(runnerName);
        runner.getMethod("start", Minecraft.class, Consumer.class, String.class).invoke(null, mc, log, "meleePathDiagnostic");
    }

    private static void checkIdle(Class<?> runnerType) throws Exception {
        var activeField = runnerType.getDeclaredField("active"); activeField.setAccessible(true);
        Class<?> core = (Class<?>) activeField.get(null);
        if (core == null) return;
        var runningField = core.getDeclaredField("running"); runningField.setAccessible(true);
        if (runningField.getBoolean(null)) throw new IllegalStateException("An integration weapon suite is still active");
    }
}
