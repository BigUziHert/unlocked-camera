package com.caleb.unlockedcamera.qa;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Prevents the unchanged broad driver from timing out while its setup mutation is still running. */
public final class BlockFreelookLoadGuardProbe {
    private static boolean registered, watching;
    private static Consumer<String> log;
    private static Field broadRunning, phase, age, task, index, mode;
    private static CompletableFuture<?> waiting;
    private static long waitingSince, lastLog, suppressedTicks, waits;
    private BlockFreelookLoadGuardProbe() {}

    public static void run(Minecraft mc, Consumer<String> output) throws Exception {
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!game.endsWith(Path.of("build", "modpack-test", "game"))
                && !game.endsWith(Path.of("build", "modpack-block-test", "game")))
            throw new IllegalStateException("Load guard requires a disposable QA copy");
        broadRunning = field("running"); phase = field("phase"); age = field("age");
        task = field("task"); index = field("index"); mode = field("mode");
        if (!broadRunning.getBoolean(null)) throw new IllegalStateException("Broad sweep is not running");
        log = output; watching = true; waiting = null; suppressedTicks = waits = 0;
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, BlockFreelookLoadGuardProbe::tick);
            registered = true;
        }
        log.accept("BLOCK_FREELOOK_LOAD_GUARD START policy=phase0_or_phase1_only;uncanceled_actual_setup_future_pending;reset_age_only;no_pose_or_result_changes;no_use_ACK_timeout_changes;controller_identity_unchanged");
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!watching) return;
        try {
            if (!broadRunning.getBoolean(null)) {
                watching = false;
                log.accept("BLOCK_FREELOOK_LOAD_GUARD DONE waits=" + waits + " suppressedClientTicks=" + suppressedTicks);
                return;
            }
            int currentPhase = phase.getInt(null);
            CompletableFuture<?> current = (CompletableFuture<?>) task.get(null);
            boolean pendingSetup = (currentPhase == 0 || currentPhase == 1)
                    && current != null && !current.isCancelled() && !current.isDone();
            long now = System.nanoTime();
            if (!pendingSetup) {
                if (waiting != null && lastLog != 0) {
                    log.accept("BLOCK_FREELOOK_LOAD_GUARD DRAINED phase=" + currentPhase + " index=" + index.getInt(null)
                            + " mode=" + mode.getInt(null) + " waitedMs=" + (now - waitingSince) / 1_000_000
                            + " futureDone=" + waiting.isDone() + " futureCanceled=" + waiting.isCancelled()
                            + " normal_join_and_errors_resume=true");
                }
                waiting = null;
                return;
            }
            if (waiting != current) {
                waiting = current; waitingSince = now; lastLog = 0; waits++;
            }
            int priorAge = age.getInt(null); age.setInt(null, 0); suppressedTicks++;
            if (now - waitingSince >= 2_000_000_000L && (lastLog == 0 || now - lastLog >= 15_000_000_000L)) {
                lastLog = now;
                log.accept("BLOCK_FREELOOK_LOAD_GUARD WAIT phase=" + currentPhase + " index=" + index.getInt(null)
                        + " mode=" + mode.getInt(null) + " priorAge=" + priorAge + " waitedMs=" + (now - waitingSince) / 1_000_000
                        + " futureDone=false futureCanceled=false");
            }
        } catch (Throwable error) {
            watching = false; log.accept("BLOCK_FREELOOK_LOAD_GUARD ERROR " + error);
        }
    }

    private static Field field(String name) throws Exception {
        Field result = BlockFreelookProbe.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
