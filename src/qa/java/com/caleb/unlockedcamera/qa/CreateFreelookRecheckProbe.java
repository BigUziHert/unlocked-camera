package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Fresh QA core and its actual event callbacks; previous aborted run remains in the log. */
public final class CreateFreelookRecheckProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.CreateInteractionScenario";
    private static Class<?> loaded;
    private CreateFreelookRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        checkIdle(CreateInteractionScenario.class);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(CreateFreelookRecheckProbe.class.getClassLoader()) {
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
        Consumer<String> renamed = line -> log.accept(line.replace("CREATE_FREELOOK", "CREATE_FREELOOK_RECHECK"));
        loaded.getMethod("run", Minecraft.class, Consumer.class, boolean.class).invoke(null, mc, renamed, true);
    }
    private static void checkIdle(Class<?> type) throws Exception {
        var active = type.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("Create scenario is still active");
    }
}
