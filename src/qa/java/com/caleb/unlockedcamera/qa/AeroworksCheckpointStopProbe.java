package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Stops only the active AeroWorks QA controller through its existing ordered restoration. */
public final class AeroworksCheckpointStopProbe {
    private static boolean registered, running, requested;
    private static Consumer<String> log;
    private static Field parentRunning, parentRestoring, parentTask;
    private static Method restore;
    private AeroworksCheckpointStopProbe() {}
    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        parentRunning = field("running"); parentRestoring = field("restoring"); parentTask = field("task");
        if (running || !parentRunning.getBoolean(null)) throw new IllegalStateException("Active AeroWorks fixture required");
        restore = AeroworksModuleProbe.class.getDeclaredMethod("restore", Minecraft.class, String.class); restore.setAccessible(true);
        log = output; running = true; requested = false;
        if (!registered) { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, AeroworksCheckpointStopProbe::tick); registered = true; }
        log.accept("AEROWORKS_CHECKPOINT_STOP START awaitingActualServerTask=true");
    }
    private static void tick(ClientTickEvent.Post ignored) {
        if (!running) return;
        try {
            if (!parentRunning.getBoolean(null)) {
                running = false; log.accept("AEROWORKS_CHECKPOINT_STOP DONE parentStopped=true verifyParentRestorationMarker=true"); return;
            }
            if (requested || parentRestoring.getBoolean(null)) return;
            Object task = parentTask.get(null);
            if (task instanceof CompletableFuture<?> future && !future.isDone()) return;
            requested = true;
            restore.invoke(null, Minecraft.getInstance(), "ABORT");
            log.accept("AEROWORKS_CHECKPOINT_STOP REQUESTED existingRestoreUsesOrderedPacketDrain=true");
        } catch (Throwable error) {
            running = false; log.accept("AEROWORKS_CHECKPOINT_STOP ERROR " + error);
        }
    }
    private static Field field(String name) throws Exception {
        Field f = AeroworksModuleProbe.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
