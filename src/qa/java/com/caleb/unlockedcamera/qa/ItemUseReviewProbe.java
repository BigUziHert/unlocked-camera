package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Reloads only the QA controller for difference-only reruns after the original sweep. */
public final class ItemUseReviewProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.ItemUseProbe";
    private static Class<?> loaded;
    private static boolean pending, registered;
    private static Consumer<String> pendingLog;
    private ItemUseReviewProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        if (Files.exists(Path.of("qa", "item-air-use", "review-hold.txt"))) {
            if (pending) throw new IllegalStateException("Item-use review already waiting for fixture readiness");
            pending = true; pendingLog = log;
            if (!registered) { NeoForge.EVENT_BUS.addListener(ItemUseReviewProbe::tick); registered = true; }
            log.accept("ITEM_AIR_USE REVIEW_HOLD waiting=qa/item-air-use/review-hold.txt");
            return;
        }
        checkIdle(ItemUseProbe.class);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(ItemUseReviewProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(TYPE) && !name.startsWith(TYPE + "$")
                        && !name.equals("com.caleb.unlockedcamera.qa.SimplySwordsQaIsolation")
                        && !name.startsWith("com.caleb.unlockedcamera.qa.SimplySwordsQaIsolation$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) {
                        try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
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
        loaded.getMethod("runReview", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!pending || Files.exists(Path.of("qa", "item-air-use", "review-hold.txt"))) return;
        pending = false;
        try {
            pendingLog.accept("ITEM_AIR_USE REVIEW_HOLD_RELEASED");
            run(Minecraft.getInstance(), pendingLog);
        } catch (Throwable failure) { pendingLog.accept("ITEM_AIR_USE ABORT review-start=" + failure); }
    }

    private static void checkIdle(Class<?> type) throws Exception {
        var active = type.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("Item-use sweep is still running");
    }
}
