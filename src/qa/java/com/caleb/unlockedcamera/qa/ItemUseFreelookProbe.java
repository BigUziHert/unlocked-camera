package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.function.Consumer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Fresh paired first-person/free-look sweep; reloads only opt-in QA classes. */
public final class ItemUseFreelookProbe {
    private static final String TYPE = "com.caleb.unlockedcamera.qa.ItemUseProbe";
    private static Class<?> loaded;
    private static boolean holding, holdRegistered;
    private static Consumer<String> heldLog;
    private static final Path HOLD = Path.of("qa", "item-freelook-hold.txt");
    private ItemUseFreelookProbe() {}

    public static void run(Minecraft mc, Consumer<String> log) throws Exception {
        if (!mc.gameDirectory.toPath().toAbsolutePath().normalize().endsWith(Path.of("build", "modpack-test", "game")))
            throw new IllegalStateException("QA reload restricted to disposable test copy");
        if (Files.exists(HOLD)) {
            if (holding) throw new IllegalStateException("Item freelook fixture is already waiting");
            holding = true; heldLog = log;
            if (!holdRegistered) {
                NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                    if (!holding || Files.exists(HOLD)) return;
                    holding = false;
                    try { run(Minecraft.getInstance(), heldLog); }
                    catch (Exception failure) { heldLog.accept("ITEM_AIR_USE ABORT deferred_start=" + failure); }
                });
                holdRegistered = true;
            }
            log.accept("ITEM_FREELOOK_MODE HOLD waiting_for_paired_melee_review;no_item_action_sent");
            return;
        }
        checkIdle(ItemUseProbe.class);
        var reviewField = ItemUseReviewProbe.class.getDeclaredField("loaded"); reviewField.setAccessible(true);
        Class<?> review = (Class<?>) reviewField.get(null);
        if (review != null) checkIdle(review);
        if (loaded != null) checkIdle(loaded);
        ClassLoader loader = new ClassLoader(ItemUseFreelookProbe.class.getClassLoader()) {
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
        loaded.getMethod("runFreelook", Minecraft.class, Consumer.class).invoke(null, mc, log);
    }

    private static void checkIdle(Class<?> type) throws Exception {
        var active = type.getDeclaredField("running"); active.setAccessible(true);
        if (active.getBoolean(null)) throw new IllegalStateException("Item-use sweep is still running");
    }
}
