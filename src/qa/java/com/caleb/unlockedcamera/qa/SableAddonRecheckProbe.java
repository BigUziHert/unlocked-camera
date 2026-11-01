package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.util.function.Consumer;

/** Fresh controller for the corrected draw-key fixture; previous results remain intact. */
public final class SableAddonRecheckProbe {
    private static Class<?> active;
    private SableAddonRecheckProbe() {}
    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        idle(SableAddonInteractionProbe.class); if (active != null) idle(active);
        String type = "com.caleb.unlockedcamera.qa.SableAddonInteractionProbe";
        active = new ClassLoader(SableAddonRecheckProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(type) && !name.startsWith(type + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        byte[] bytes = stream.readAllBytes(); loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    if (resolve) resolveClass(loaded); return loaded;
                }
            }
        }.loadClass(type);
        Consumer<String> output = line -> log.accept(line.replaceFirst("^SABLE_ADDON_ACTION", "SABLE_ADDON_RECHECK")
                .replaceFirst("^SABLE_ADDON_PRIORITY", "SABLE_ADDON_PRIORITY_RECHECK"));
        active.getMethod("run", Minecraft.class, Consumer.class).invoke(null, mc, output);
    }
    private static void idle(Class<?> controller) throws Exception {
        var running = controller.getDeclaredField("running"); running.setAccessible(true);
        if (running.getBoolean(null)) throw new IllegalStateException("Previous Sable addon controller is still active");
    }
}
